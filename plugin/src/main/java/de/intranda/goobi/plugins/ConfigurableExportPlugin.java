package de.intranda.goobi.plugins;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    @Getter
    private PluginType type = PluginType.Export;

    @Getter
    private String title = "intranda_export_configurable";

    private boolean embedMarc;
    private Project oldProject;

    private String[] imageFolders;
    private boolean includeDerivate;
    private boolean includeMaster;
    private boolean includeOcr;
    private boolean includeSource;
    private boolean includeImport;
    private boolean includeExort;
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

        // read configuration
        this.myPrefs = process.getRegelsatz().getPreferences();
        SubnodeConfiguration config = getConfig(process);
        embedMarc = config.getBoolean("./includeMarcXml", false);
        processId = process.getId();
        log.debug("Export Process ID: " + processId);
        //save current project
        oldProject = process.getProjekt();
        log.debug("Export Original Project: " + oldProject.getTitel());
        
        imageFolders = config.getStringArray("./folder/genericFolder");
        includeDerivate = config.getBoolean("./folder/includeMedia", false);
        includeMaster = config.getBoolean("./folder/includeMaster", false);
        includeOcr = config.getBoolean("./folder/includeOcr", false);
        includeSource = config.getBoolean("./folder/includeSource", false);
        includeImport = config.getBoolean("./folder/includeImport", false);
        includeExort = config.getBoolean("./folder/includeExort", false);
        includeITM = config.getBoolean("./folder/includeITM", false);
        includeValidation = config.getBoolean("./folder/includeValidation", false);
        ocrSuffix = config.getStringArray("./folder/ocr/suffix");

        String[] targetProjectNames = config.getStringArray("./target/@projectName");
        String[] targetKeys = config.getStringArray("./target/@key");
        String[] targetValues = config.getStringArray("./target/@value");
        ArrayList<Project> matchedProjects = new ArrayList<Project>();

        if (targetProjectNames.length != targetKeys.length && targetProjectNames.length != targetKeys.length) {
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
                    }
                    else {
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

        if (targetProjectNames.length >= 1) {
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
                // if runExport throws an Exception make sure the project is reset before rethrowing
                process.setProjekt(oldProject);
                throw ex;
            }
            return true;
        } else {
            return runExport(process);
        }
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

        Path temporaryFile = StorageProvider.getInstance().createTemporaryFile(process.getTitel(), ".xml");
        writeMetsFile(process, temporaryFile.toString(), gdzfile, false);

        DigitalDocument digDoc = gdzfile.getDigitalDocument();
        DocStruct logical = digDoc.getLogicalDocStruct();
        VariableReplacer replacer = new VariableReplacer(digDoc, this.myPrefs, process, null);

        //Variabes METS/MARC-Export
        String idDigital = null;
        String idSource = null;
        String anchorIdDigital = null;
        String anchorIdSource = null;
        String exportRootDirectory = replacer.replace(process.getProjekt().getDmsImportImagesPath());

        // prepare folder
        Path derivateFolder = null;
        Path masterFolder = null;
        Path ocrFolder = null;
        Path sourceFolder = null;
        Path importFolder = null;
        Path exportFolder = null;
        Path itmFolder = null;
        Path validationFolder = null;

        if (includeDerivate) {
            derivateFolder = Paths.get(process.getImagesTifDirectory(false));
        }
        if (includeMaster) {
            masterFolder = Paths.get(process.getImagesOrigDirectory(false));
        }
        if (includeOcr) {
            ocrFolder = Paths.get(process.getOcrDirectory());
        }
        if (includeSource) {
            sourceFolder = Paths.get(process.getSourceDirectory());
        }
        if (includeImport) {
            importFolder = Paths.get(process.getImportDirectory());
        }
        if (includeExort) {
            exportFolder = Paths.get(process.getExportDirectory());
        }
        if (includeITM) {
            itmFolder = Paths.get(process.getProcessDataDirectory() + "taskmanager");
        }
        if (includeValidation) {
            validationFolder = Paths.get(process.getProcessDataDirectory() + "validation");
        }

        DocStruct anchor = null;

        //read INFOS for METS/MARC Export
        if (logical.getType().isAnchor()) {
            anchor = logical;
            logical = logical.getAllChildren().get(0);
        }
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
        trimAllMetadata(gdzfile.getDigitalDocument().getLogicalDocStruct());

        /*
         * -------------------------------- Metadaten validieren --------------------------------
         */

        if (ConfigurationHelper.getInstance().isUseMetadataValidation()) {
            MetadatenVerifizierung mv = new MetadatenVerifizierung();
            if (!mv.validate(gdzfile, this.myPrefs, process)) {
                problems.add("Export cancelled because of validation errors");
                problems.addAll(mv.getProblems());
                process.setProjekt(oldProject);
                return false;
            }
        }

        Path destination;
        if (process.getProjekt().isDmsImportCreateProcessFolder()) {
            destination = Paths.get(exportRootDirectory, process.getTitel());
        } else {
            destination = Paths.get(exportRootDirectory);
        }
        log.debug("Export directory: " + destination);
        if (!Files.exists(destination)) {
            Files.createDirectories(destination);
            log.debug("Export directory created as it did not exist");
        }
        // copy folder to destination
        if (derivateFolder != null && Files.exists(derivateFolder)) {
            StorageProvider.getInstance().copyDirectory(derivateFolder, Paths.get(destination.toString(), derivateFolder.getFileName().toString()));
            log.debug("Export copy derivatives from " + derivateFolder + " to " + Paths.get(destination.toString(), derivateFolder.getFileName().toString()));
        }
        if (masterFolder != null && Files.exists(masterFolder)) {
            StorageProvider.getInstance().copyDirectory(masterFolder, Paths.get(destination.toString(), masterFolder.getFileName().toString()));
            log.debug("Export copy masters from " + masterFolder + " to " + Paths.get(destination.toString(), masterFolder.getFileName().toString()));
        }
        for (String configuredFolder : imageFolders) {
            Path folderPath = Paths.get(process.getConfiguredImageFolder(configuredFolder));
            if (Files.exists(folderPath)) {
                StorageProvider.getInstance().copyDirectory(folderPath, destination.resolve(folderPath.getFileName().toString()));
                log.debug("Export copy folder from " + folderPath + " to " + destination.resolve(folderPath.getFileName().toString()));
                
            }
        }
        if (ocrFolder != null && Files.exists(ocrFolder)) {

            Set<String> ocrSuffixes = new HashSet<>(Arrays.asList(ocrSuffix));
            List<Path> ocrData = StorageProvider.getInstance().listFiles(ocrFolder.toString());

            for (Path path : ocrData) {
                String suffix = getOcrPathSuffix(path);
                if (ocrSuffixes == null || ocrSuffixes.size() == 0 || ocrSuffixes.contains(suffix)) {
                    if (Files.isDirectory(path)) {
                        StorageProvider.getInstance().copyDirectory(path, Paths.get(destination.toString(), path.getFileName().toString()));
                        log.debug("Export copy ocr data from " + path + " to " + Paths.get(destination.toString(), path.getFileName().toString()));
                    } else {
                        StorageProvider.getInstance().copyFile(path, Paths.get(destination.toString(), path.getFileName().toString()));
                        log.debug("Export copy ocr data from " + path + " to " + Paths.get(destination.toString(), path.getFileName().toString()));
                    }
                }
            }
        }
        if (sourceFolder != null && Files.exists(sourceFolder)) {
            StorageProvider.getInstance().copyDirectory(sourceFolder, Paths.get(destination.toString(), process.getTitel() + "_source"));
            log.debug("Export copy sourceFolder from " + sourceFolder + " to " +  Paths.get(destination.toString(), process.getTitel() + "_source"));
        }
        if (importFolder != null && Files.exists(importFolder)) {
            StorageProvider.getInstance().copyDirectory(importFolder, Paths.get(destination.toString(), process.getTitel() + "_import"));
            log.debug("Export copy importFolder from " + importFolder + " to " + Paths.get(destination.toString(), process.getTitel() + "_import"));
        }
        if (exportFolder != null && Files.exists(exportFolder)) {
            StorageProvider.getInstance().copyDirectory(exportFolder, Paths.get(destination.toString(), process.getTitel() + "_export"));
            log.debug("Export copy exportFolder from " + exportFolder + " to " + Paths.get(destination.toString(), process.getTitel() + "_export"));
        }
        if (itmFolder != null && Files.exists(itmFolder)) {
            StorageProvider.getInstance().copyDirectory(itmFolder, Paths.get(destination.toString(), itmFolder.getFileName().toString()));
            log.debug("Export copy itmFolder from " + itmFolder + " to " + Paths.get(destination.toString(), itmFolder.getFileName().toString()));
        }
        if (derivateFolder != null && Files.exists(derivateFolder)) {
            StorageProvider.getInstance().copyDirectory(derivateFolder, Paths.get(destination.toString(), derivateFolder.getFileName().toString()));
            log.debug("Export copy derivateFolder from " + derivateFolder + " to " + Paths.get(destination.toString(), derivateFolder.getFileName().toString()));
        }
        if (validationFolder != null && Files.exists(validationFolder)) {
            StorageProvider.getInstance()
                    .copyDirectory(validationFolder, Paths.get(destination.toString(), validationFolder.getFileName().toString()));
            log.debug("Export copy validationFolder from " + validationFolder + " to " + Paths.get(destination.toString(), validationFolder.getFileName().toString()));
        }

        // check, if import/xxxx_marc.xml exists
        Path importDirectory = Paths.get(process.getImportDirectory());
        Path anchorFile = Paths.get(temporaryFile.getParent().toString(), temporaryFile.getFileName().toString().replace(".xml", "_anchor.xml"));
        if (Files.exists(importDirectory)) {
            List<Path> filesInFolder = StorageProvider.getInstance().listFiles(importDirectory.toString());

            Path sourceMarcFile = null;
            Path digitalMarcFile = null;
            Path anchorSourceMarcFile = null;
            Path anchorDigitalMarcFile = null;
            Path metsFile = Paths.get(destination.toString(), process.getTitel() + ".xml");

            for (Path path : filesInFolder) {
                if (path.getFileName().toString().endsWith(idSource + "_marc.xml")) {
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
            if (embedMarc && (digitalMarcFile != null || sourceMarcFile != null)) {
                updateXmlFile(sourceMarcFile, digitalMarcFile, temporaryFile);
            }
            if (embedMarc && Files.exists(anchorFile) && (anchorSourceMarcFile != null || anchorDigitalMarcFile != null)) {
                updateXmlFile(anchorSourceMarcFile, anchorDigitalMarcFile, anchorFile);
            }
        }

        // Copy temporary MetsFile to Destination and delete temporary file
        Path exportedMetsFile = Paths.get(destination.toString(), process.getTitel() + ".xml");
        StorageProvider.getInstance().copyFile(temporaryFile, exportedMetsFile);
        log.debug("Export copy temporaryFile from " + temporaryFile + " to " + exportedMetsFile);
        
        if (StorageProvider.getInstance().isFileExists(anchorFile)) {
            StorageProvider.getInstance()
                    .copyFile(anchorFile, Paths.get(exportedMetsFile.getParent().toString(),
                            exportedMetsFile.getFileName().toString().replace(".xml", "_anchor.xml")));
            log.debug("Export copy anchorFile from " + anchorFile + " to " + Paths.get(exportedMetsFile.getParent().toString(),
                    exportedMetsFile.getFileName().toString().replace(".xml", "_anchor.xml")));
            StorageProvider.getInstance().deleteDir(anchorFile);
            log.debug("Export delete file " + anchorFile);
        }
        StorageProvider.getInstance().deleteDir(temporaryFile);
        log.debug("Export delete folder " + temporaryFile);
        
        process.setProjekt(oldProject);
        //in case there are other exports set the exportRootDirectory to null
        exportRootDirectory = null;
        return true;
    }

    /**
     * 
     * @param sourceMarcFile
     * @param digitalMarcFile
     * @param metsFile
     * @return
     */
    private boolean updateXmlFile(Path sourceMarcFile, Path digitalMarcFile, Path metsFile) {
        SAXBuilder parser = new SAXBuilder();
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
