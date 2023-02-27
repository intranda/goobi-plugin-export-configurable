package de.intranda.goobi.plugins;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.goobi.beans.Process;
import org.goobi.beans.Project;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IExportPlugin;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.Namespace;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.export.dms.ExportDms;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.XmlTools;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ExportFileException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.exceptions.UghHelperException;
import de.sub.goobi.metadaten.MetadatenHelper;
import de.sub.goobi.metadaten.MetadatenVerifizierung;
import de.sub.goobi.persistence.managers.ProjectManager;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.ExportFileformat;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;

@PluginImplementation
@Log4j2

public class ConfigurableExportPlugin extends ExportDms implements IExportPlugin, IPlugin {

    private static final Namespace metsNamespace = Namespace.getNamespace("mets", "http://www.loc.gov/METS/");
    private static final String FOLDERS_PARENT_ELEMENT = "includeFolders";
    private static final String GENERIC_FOLDER = "genericFolder";
    @Getter
    private PluginType type = PluginType.Export;

    @Getter
    private String title = "intranda_export_configurable";

    private boolean embedMarc;
    private Project oldProject;

    private SubnodeConfiguration foldersConfig;

    private String[] imageFolders;
    private boolean includeDerivate;
    private boolean includeMaster;
    private boolean includeOcr;
    private boolean includeSource;
    private boolean includeImport;
    private boolean includeExport;
    private boolean includeITM;
    private boolean includeValidation;
    private Integer processId;
    String[] ocrSuffix;

    public SubnodeConfiguration getConfig(Process process) {
        String projectName = process.getProjekt().getTitel();
        XMLConfiguration xmlConfig = ConfigPlugins.getPluginConfig(title);
        xmlConfig.setExpressionEngine(new XPathExpressionEngine());
        xmlConfig.setReloadingStrategy(new FileChangedReloadingStrategy());
        SubnodeConfiguration conf = null;

        // order of configuration is:
        // 1.) project name matches
        // 2.) project is *
        try {
            conf = xmlConfig.configurationAt("//config[./project = '" + projectName + "']");
        } catch (IllegalArgumentException e) {
            conf = xmlConfig.configurationAt("//config[./project = '*']");
        }
        return conf;
    }

    @Override
    public boolean startExport(Process process, String inZielVerzeichnis)
            throws IOException, InterruptedException, WriteException, PreferencesException, DocStructHasNoTypeException,
            MetadataTypeNotAllowedException, ExportFileException, UghHelperException, SwapException, DAOException, TypeNotAllowedForParentException {

        log.debug("================= Starting Configurable Export Plugin =================");

        // read configuration
        this.myPrefs = process.getRegelsatz().getPreferences();
        SubnodeConfiguration config = getConfig(process);

        initializePrivateFields(process, config);

        String[] targetProjectNames = config.getStringArray("./target/@projectName");
        String[] targetKeys = config.getStringArray("./target/@key");
        String[] targetValues = config.getStringArray("./target/@value");
        ArrayList<Project> matchedProjects = new ArrayList<>();

        if (targetProjectNames.length != targetKeys.length) {
            String message = "Malformated Configurationfile: Missing Attribute in target tag!";
            log.error(message);
            Helper.setFehlerMeldung(null, process.getTitel() + ": ", message);
            Helper.addMessageToProcessLog(process.getId(), LogType.DEBUG, message);
            problems.add(message);
            return false;
        }

        VariableReplacer replacer;
        try {
            replacer = new VariableReplacer(process.readMetadataFile().getDigitalDocument(), this.myPrefs, process, null);
        } catch (ReadException ex) {
            String message = "Couldn't create Variable replacer!";
            Helper.setFehlerMeldung(message, ex);
            Helper.addMessageToProcessLog(process.getId(), LogType.DEBUG, message);
            log.error(message, ex);
            problems.add(message + ex.getMessage());
            return false;
        }

        for (int i = 0; i < targetProjectNames.length; i++) {
            targetKeys[i] = replacer.replace(targetKeys[i]);
            if (targetKeys[i] != null && targetKeys[i].equals(targetValues[i])) {
                try {
                    if (targetProjectNames[i].trim().isBlank()) {
                        matchedProjects.add(oldProject);
                    } else {
                        matchedProjects.add(ProjectManager.getProjectByName(targetProjectNames[i].trim()));
                    }

                } catch (DAOException ex) {
                    String message = "Export cancelled! A target condition was met but the project " + targetProjectNames[i]
                            + " does not exist. Please update the configuration file!";
                    log.error(message, ex);
                    Helper.setFehlerMeldung(message, ex);
                    Helper.addMessageToProcessLog(process.getId(), LogType.DEBUG, message);
                    problems.add(message + ex.getMessage());
                    return false;
                }
            }
        }

        if (targetProjectNames.length < 1) {
            return runExport(process);
        }

        // targetProjectNames.length >= 1
        try {
            for (Project project : matchedProjects) {
                process.setProjekt(project);
                if (!runExport(process)) {
                    String message = "Export cancelled! Export with Parameters of Project" + project.getTitel() + "failed!";
                    log.error(message);
                    problems.add(message);
                    Helper.setMeldung(null, process.getTitel() + ": ", message);
                    Helper.addMessageToProcessLog(process.getId(), LogType.DEBUG, message);
                    process.setProjekt(oldProject);
                    return false;
                }
            }
        } catch (IOException | InterruptedException | SwapException | DAOException | PreferencesException | WriteException
                | TypeNotAllowedForParentException ex) {
            // if runExport throws an Exception make sure the project is reset before
            // rethrowing
            process.setProjekt(oldProject);
            throw ex;
        }

        return true;
    }

    private void initializePrivateFields(Process process, SubnodeConfiguration config) {
        embedMarc = config.getBoolean("./includeMarcXml", false);
        processId = process.getId();
        log.debug("Export Plugin - Process ID: " + processId);
        // save current project
        oldProject = process.getProjekt();
        log.debug("Export Plugin - Original Project: " + oldProject.getTitel());

        foldersConfig = config.configurationAt(FOLDERS_PARENT_ELEMENT);

        imageFolders = foldersConfig.getStringArray(GENERIC_FOLDER);
        includeDerivate = foldersConfig.getBoolean("./media/@enabled", false);
        includeMaster = foldersConfig.getBoolean("./master/@enabled", false);
        includeOcr = foldersConfig.getBoolean("./ocr/@enabled", false);
        includeSource = foldersConfig.getBoolean("./source/@enabled", false);
        includeImport = foldersConfig.getBoolean("./import/@enabled", false);
        includeExport = foldersConfig.getBoolean("./export/@enabled", false);
        includeITM = foldersConfig.getBoolean("./itm/@enabled", false);
        includeValidation = foldersConfig.getBoolean("./validation/@enabled", false);
        ocrSuffix = foldersConfig.getStringArray("./ocr/sourceFolderSuffix");
    }

    /**
     * executes an Export for a given process
     * 
     * @param process process that shall be exported
     * @return
     * @throws IOException
     * @throws InterruptedException
     * @throws SwapException
     * @throws DAOException
     * @throws PreferencesException
     * @throws WriteException
     * @throws TypeNotAllowedForParentException
     */
    private boolean runExport(Process process) throws IOException, InterruptedException, SwapException, DAOException, PreferencesException,
            WriteException, TypeNotAllowedForParentException {
        Fileformat gdzfile;
        ExportFileformat newfile = MetadatenHelper.getExportFileformatByName(process.getProjekt().getFileFormatDmsExport(), process.getRegelsatz());
        try {
            gdzfile = process.readMetadataFile();
            newfile.setDigitalDocument(gdzfile.getDigitalDocument());
            gdzfile = newfile;

        } catch (Exception e) {
            String message = "Export canceled opening FileFormat or reading DigitalDocument: ";
            Helper.setFehlerMeldung(Helper.getTranslation("exportError") + process.getTitel(), e);
            Helper.addMessageToProcessLog(process.getId(), LogType.DEBUG, message);
            log.error(message, e);
            problems.add(message + e.getMessage());
            return false;
        }

        // use the system tmp folder

        // use the goobi internal temp folder instead of the system one
        Path temporaryFile = Paths.get(ConfigurationHelper.getInstance().getTemporaryFolder(), process.getTitel() + ".xml");
        if (StorageProvider.getInstance().isFileExists(temporaryFile)) {
            StorageProvider.getInstance().deleteFile(temporaryFile);
        }
        StorageProvider.getInstance().createFile(temporaryFile);

        // write mets file to temp folder
        writeMetsFile(process, temporaryFile.toString(), gdzfile, false);

        trimAllMetadata(gdzfile.getDigitalDocument().getLogicalDocStruct());

        // validate Metadaten
        if (ConfigurationHelper.getInstance().isUseMetadataValidation()) {
            MetadatenVerifizierung mv = new MetadatenVerifizierung();
            if (!mv.validate(gdzfile, this.myPrefs, process)) {
                problems.add("Export cancelled because of validation errors");
                problems.addAll(mv.getProblems());
                process.setProjekt(oldProject);
                return false;
            }
        }

        // prepare destination path
        DigitalDocument digDoc = gdzfile.getDigitalDocument();
        VariableReplacer replacer = new VariableReplacer(digDoc, this.myPrefs, process, null);
        String exportRootDirectory = replacer.replace(process.getProjekt().getDmsImportImagesPath());

        Path destination;
        if (process.getProjekt().isDmsImportCreateProcessFolder()) {
            destination = Paths.get(exportRootDirectory, process.getTitel());
        } else {
            destination = Paths.get(exportRootDirectory);
        }
        log.debug("Export Plugin - directory: " + destination);
        if (!StorageProvider.getInstance().isFileExists(destination)) {
            StorageProvider.getInstance().createDirectories(destination);
            log.debug("Export Plugin - directory created as it did not exist");
        }

        // copy folders to destination
        performCopyFolders(process, destination);

        // copy the METS file to destination
        Path exportedMetsFile = Paths.get(destination.toString(), process.getTitel() + ".xml");
        StorageProvider.getInstance().copyFile(temporaryFile, exportedMetsFile);

        // perform METS/MARC-Export
        Path importDirectory = Paths.get(process.getImportDirectory());
        // check, if import/xxxx_marc.xml exists
        if (StorageProvider.getInstance().isFileExists(importDirectory)) {
            List<Path> filesInFolder = StorageProvider.getInstance().listFiles(importDirectory.toString());
            performMetsMarcExport(digDoc, temporaryFile, exportedMetsFile, filesInFolder);
        }

        // delete the temporary METS file
        StorageProvider.getInstance().deleteFile(temporaryFile);
        log.debug("Export Plugin - delete file " + temporaryFile);

        process.setProjekt(oldProject);

        return true;
    }

    private void performCopyFolders(Process process, Path destination) throws IOException, SwapException, DAOException {
        if (includeDerivate) {
            getFolderAndCopyFolderToDestination(process, destination, "media");
        }
        if (includeMaster) {
            getFolderAndCopyFolderToDestination(process, destination, "master");
        }
        if (includeOcr) {
            getFolderAndCopyFolderToDestination(process, destination, "ocr");
        }
        if (includeSource) {
            getFolderAndCopyFolderToDestination(process, destination, "source");
        }
        if (includeImport) {
            getFolderAndCopyFolderToDestination(process, destination, "import");
        }
        if (includeExport) {
            getFolderAndCopyFolderToDestination(process, destination, "export");
        }
        if (includeITM) {
            getFolderAndCopyFolderToDestination(process, destination, "itm");
        }
        if (includeValidation) {
            getFolderAndCopyFolderToDestination(process, destination, "validation");
        }

        // process generic folders
        List<HierarchicalConfiguration> genericFolderConfigNodes = foldersConfig.configurationsAt(GENERIC_FOLDER);

        for (int i = 0; i < imageFolders.length; ++i) {
            String configuredFolder = imageFolders[i];
            Path folderPath = Paths.get(process.getConfiguredImageFolder(configuredFolder));
            log.debug("configuredFolder = " + configuredFolder);
            log.debug("folderPath = " + folderPath);
            SubnodeConfiguration genericFolderConfig = (SubnodeConfiguration) genericFolderConfigNodes.get(i);
            getDestPathAndCopyFolder(process, genericFolderConfig, folderPath, destination, GENERIC_FOLDER);
        }
    }

    private void getFolderAndCopyFolderToDestination(Process process, Path destination, String folderType)
            throws IOException, SwapException, DAOException {
        Path fromPath = getSourcePathForCopy(process, folderType);
        if (fromPath == null || !StorageProvider.getInstance().isFileExists(fromPath)) {
            return;
        }
        if (folderType.equals("ocr")) {
            copyOcrFolderToDestination(process, fromPath, destination);
        } else {
            SubnodeConfiguration subnodeConfig = foldersConfig.configurationAt(folderType);
            getDestPathAndCopyFolder(process, subnodeConfig, fromPath, destination, folderType);
        }
    }

    private Path getSourcePathForCopy(Process process, String folderType) throws IOException, SwapException, DAOException {
        switch (folderType) {
            case "media":
                return Paths.get(process.getImagesTifDirectory(false));
            case "master":
                return Paths.get(process.getImagesOrigDirectory(false));
            case "ocr":
                return Paths.get(process.getOcrDirectory());
            case "source":
                return Paths.get(process.getSourceDirectory());
            case "import":
                return Paths.get(process.getImportDirectory());
            case "export":
                return Paths.get(process.getExportDirectory());
            case "itm":
                return Paths.get(process.getProcessDataDirectory() + "taskmanager");
            case "validation":
                return Paths.get(process.getProcessDataDirectory() + "validation");
            default:
                return null;
        }
    }

    private void getDestPathAndCopyFolder(Process process, SubnodeConfiguration subnodeConfig, Path fromPath, Path destination, String folderType)
            throws IOException {
        // get the values of the subelement <destinationFolder />
        HashMap<String, String> destFolderMap = getDestFolderMap(subnodeConfig);

        if (destFolderMap.isEmpty()) {
            log.debug("no destinationFolder specified for '" + folderType + "', using default settings instead...");
            Path toPath = getDefaultDestPathForCopy(process, fromPath, destination, folderType);
            copyFolderToDestination(fromPath, toPath, folderType);
            return;
        }

        // destFolderMap is not empty
        log.debug("destinationFolder specified for '" + folderType + "' with destFolderMap.size() = " + destFolderMap.size());

        for (Map.Entry<String, String> entry : destFolderMap.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            // create a folder by the name of key
            Path toPath = Paths.get(destination.toString(), key);
            if (!StorageProvider.getInstance().isFileExists(toPath)) {
                StorageProvider.getInstance().createDirectories(toPath);
            }

            if (value.equals("")) {
                log.debug("No pattern specified, '" + key + "' will be left empty.");
                continue;
            }

            log.debug("Files matching the pattern '" + value + "' should be copied to -> '" + key + "'");

            // get a Pattern using value
            Pattern pattern = Pattern.compile(value);

            // iterate over the files in fromPath, copy everything whose name matches the Pattern to this new folder
            List<Path> files = StorageProvider.getInstance().listFiles(fromPath.toString());
            for (Path file : files) {
                String fileName = file.getFileName().toString();
                Matcher matcher = pattern.matcher(fileName);
                if (matcher.find()) {
                    Path newFilePath = toPath.resolve(fileName);
                    StorageProvider.getInstance().copyFile(file, newFilePath);
                }
            }
        }
    }

    private Path getDefaultDestPathForCopy(Process process, Path fromPath, Path destination, String folderType) {
        switch(folderType) {
            case "source":
                return Paths.get(destination.toString(), process.getTitel() + "_source");
            case "import":
                return Paths.get(destination.toString(), process.getTitel() + "_import");
            case "export":
                return Paths.get(destination.toString(), process.getTitel() + "_export");
            case GENERIC_FOLDER:
                return destination.resolve(fromPath.getFileName().toString());
            default:
                return Paths.get(destination.toString(), fromPath.getFileName().toString());
        }
    }

    private HashMap<String, String> getDestFolderMap(SubnodeConfiguration subnodeConfig) {
        HashMap<String, String> destFolderMap = new HashMap<>();
        List<HierarchicalConfiguration> destinationFolderList = subnodeConfig.configurationsAt("./destinationFolder");
        for (HierarchicalConfiguration destConfig : destinationFolderList) {
            String name = destConfig.getString("./@name");
            String exportFileRegex = destConfig.getString("./@exportFileRegex", "");
            destFolderMap.put(name, exportFileRegex);
        }
        return destFolderMap;
    }

    private void copyFolderToDestination(Path fromPath, Path toPath, String folderType) throws IOException {
        if (StorageProvider.getInstance().isFileExists(fromPath)) {
            String debugInfo = getDebugInfo(fromPath, toPath, folderType);
            StorageProvider.getInstance().copyDirectory(fromPath, toPath, false);
            log.debug(debugInfo);
        }
    }

    private String getDebugInfo(Path fromPath, Path toPath, String type) {
        if (type.equals("ocr")) {
            return "Export copy ocr data from " + fromPath + " to " + toPath;
        }
        String specialInfo = type; // by default
        switch (type) {
            case "media":
                specialInfo = "derivates";
                break;
            case "master":
                specialInfo = "masters";
                break;
            case "source":
                specialInfo = "sourceFolder";
                break;
            case "import":
                specialInfo = "importFolder";
                break;
            case "export":
                specialInfo = "exportFolder";
                break;
            case "itm":
                specialInfo = "itmFolder";
                break;
            case "validation":
                specialInfo = "validationFolder";
                break;
        }
        return "Export Plugin - copy " + specialInfo + " from " + fromPath + " to " + toPath;
    }

    private void copyOcrFolderToDestination(Process process, Path ocrFolder, Path destination) throws IOException {

        Set<String> ocrSuffixes = new HashSet<>(Arrays.asList(ocrSuffix));
        List<Path> ocrData = StorageProvider.getInstance().listFiles(ocrFolder.toString());

        for (Path path : ocrData) {
            String suffix = getOcrPathSuffix(path);
            Path toPath = getDefaultDestPathForCopy(process, path, destination, "ocr");
            String debugInfo = getDebugInfo(path, toPath, "ocr");
            if (ocrSuffixes.isEmpty() || ocrSuffixes.contains(suffix)) {
                if (StorageProvider.getInstance().isDirectory(path)) {
                    StorageProvider.getInstance().copyDirectory(path, toPath, false);
                    log.debug(debugInfo);
                } else {
                    StorageProvider.getInstance().copyFile(path, toPath);
                    log.debug(debugInfo);
                }
            }
        }
    }

    private void performMetsMarcExport(DigitalDocument digDoc, Path temporaryFile, Path destination,
            List<Path> filesInFolder) throws IOException {

        Path anchorFile = Paths.get(temporaryFile.getParent().toString(), temporaryFile.getFileName().toString().replace(".xml", "_anchor.xml")); //NOSONAR
        // update MARC files
        if (embedMarc) {
            updateMarcFiles(digDoc, temporaryFile, anchorFile, filesInFolder);
        }

        // Copy temporary MetsFile to Destination and delete temporary file
        moveTempMetsFileToDestination(temporaryFile, destination, anchorFile);
    }

    private void updateMarcFiles(DigitalDocument digDoc, Path temporaryFile, Path anchorFile, List<Path> filesInFolder) {
        DocStruct logical = digDoc.getLogicalDocStruct();
        DocStruct anchor = null;
        if (logical.getType().isAnchor()) {
            anchor = logical;
            logical = logical.getAllChildren().get(0);
        }

        // Variabes METS/MARC-Export
        String idDigital = null;
        String idSource = null;
        String anchorIdDigital = null;
        String anchorIdSource = null;
        // read INFOS for METS/MARC Export
        for (Metadata md : logical.getAllMetadata()) {
            if ("CatalogIDSource".equals(md.getType().getName())) {
                idSource = md.getValue();
            } else if ("CatalogIDDigital".equals(md.getType().getName())) {
                idDigital = md.getValue();
            }
        }
        if (anchor != null) {
            for (Metadata md : anchor.getAllMetadata()) {
                if ("CatalogIDSource".equals(md.getType().getName())) {
                    anchorIdSource = md.getValue();
                } else if ("CatalogIDDigital".equals(md.getType().getName())) {
                    anchorIdDigital = md.getValue();
                }
            }
        }

        Path sourceMarcFile = null;
        Path digitalMarcFile = null;
        Path anchorSourceMarcFile = null;
        Path anchorDigitalMarcFile = null;

        for (Path path : filesInFolder) {
            if (path.getFileName().toString().endsWith(idSource + "_marc.xml")) { //NOSONAR
                sourceMarcFile = path;
            } else if (path.getFileName().toString().endsWith(idDigital + "_marc.xml")) {
                digitalMarcFile = path;
            } else if (path.getFileName().toString().endsWith(anchorIdDigital + "_marc.xml")) {
                anchorDigitalMarcFile = path;
            } else if (path.getFileName().toString().endsWith(anchorIdSource + "_marc.xml")) {
                anchorSourceMarcFile = path;
            }
        }

        // found marc file for monograph/volume
        if (digitalMarcFile != null || sourceMarcFile != null) {
            updateXmlFile(sourceMarcFile, digitalMarcFile, temporaryFile);
        }
        if (StorageProvider.getInstance().isFileExists(anchorFile) && (anchorSourceMarcFile != null || anchorDigitalMarcFile != null)) {
            updateXmlFile(anchorSourceMarcFile, anchorDigitalMarcFile, anchorFile);
        }
    }

    private void moveTempMetsFileToDestination(Path temporaryFile, Path exportedMetsFile, Path anchorFile) throws IOException {
        String debugInfo = getDebugInfo(temporaryFile, exportedMetsFile, "temporaryFile");
        log.debug(debugInfo);

        if (StorageProvider.getInstance().isFileExists(anchorFile)) {
            Path toPath = Paths.get(exportedMetsFile.getParent().toString(),
                    exportedMetsFile.getFileName().toString().replace(".xml", "_anchor.xml"));
            StorageProvider.getInstance().copyFile(anchorFile, toPath);
            debugInfo = getDebugInfo(anchorFile, toPath, "anchorFile");
            log.debug(debugInfo);
            // deleteDir (?)
            StorageProvider.getInstance().deleteDir(anchorFile);
            log.debug("Export Plugin - delete file " + anchorFile);
        }
    }

    /**
     * 
     * @param sourceMarcFile
     * @param digitalMarcFile
     * @param metsFile
     * @return
     */
    private boolean updateXmlFile(Path sourceMarcFile, Path digitalMarcFile, Path metsFile) {
        SAXBuilder parser = XmlTools.getSAXBuilder();
        try {
            Element sourceMarcElement = null;
            Element digitalMarcElement = null;
            if (sourceMarcFile != null) {
                Document marcDoc = parser.build(sourceMarcFile.toString());
                sourceMarcElement = marcDoc.getRootElement();
                sourceMarcElement.detach();
            }

            if (digitalMarcFile != null) {
                Document marcDoc = parser.build(digitalMarcFile.toString());
                digitalMarcElement = marcDoc.getRootElement();
                digitalMarcElement.detach();
            }

            Document metsDoc = parser.build(metsFile.toString());
            Element metsElement = metsDoc.getRootElement();
            Element firstDmdSecElement = metsElement.getChild("dmdSec", metsNamespace);

            Element mdWrap = new Element("mdWrap", metsNamespace);
            mdWrap.setAttribute("MDTYPE", "MARC");
            firstDmdSecElement.addContent(mdWrap);

            Element xmlData = new Element("xmlData", metsNamespace);
            mdWrap.addContent(xmlData);
            if (digitalMarcFile != null) {
                digitalMarcElement.setName("marc");
                xmlData.addContent(digitalMarcElement);
            }
            if (sourceMarcElement != null) {
                sourceMarcElement.setName("marc");
                xmlData.addContent(sourceMarcElement);
            }

            XMLOutputter outputter = new XMLOutputter(Format.getPrettyFormat());

            FileOutputStream output = new FileOutputStream(metsFile.toString());
            outputter.output(metsDoc, output);

        } catch (JDOMException | IOException e) {
            String message = "Cannot add marc file to process: ";
            Helper.setFehlerMeldung(message + metsFile.toString(), e);
            Helper.addMessageToProcessLog(processId, LogType.DEBUG, message);
            problems.add("Cannot add marc file to process: " + e.getMessage());
            return false;
        }
        return true;
    }

    public String getOcrPathSuffix(Path path) {
        String name = path.getFileName().toString();
        String suffix = "";
        String[] suffixSplit = name.split("_");
        if (suffixSplit != null && suffixSplit.length > 0) {
            suffix = suffixSplit[suffixSplit.length - 1];
        }
        return suffix;
    }

}