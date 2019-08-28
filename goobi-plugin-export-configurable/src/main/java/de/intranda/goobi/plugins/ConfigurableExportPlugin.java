package de.intranda.goobi.plugins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang.StringUtils;
import org.goobi.beans.Process;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IExportPlugin;
import org.goobi.production.plugin.interfaces.IPlugin;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigProjects;
import de.sub.goobi.export.dms.ExportDms;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ExportFileException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.exceptions.UghHelperException;
import de.sub.goobi.metadaten.MetadatenHelper;
import lombok.Getter;
import lombok.extern.log4j.Log4j;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.ExportFileformat;
import ugh.dl.Fileformat;
import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;

@PluginImplementation
@Log4j

public class ConfigurableExportPlugin extends ExportDms implements IExportPlugin, IPlugin {

    @Getter
    private PluginType type = PluginType.Export;

    @Getter
    private String title = "intranda_export_configurable";

    private String exportRootDirectory;
    private boolean useSubFolderPerProcess;
    private boolean includeDerivate;
    private boolean includeMaster;
    private boolean includeOcr;
    private boolean includeSource;

    private boolean includeImport;
    private boolean includeExort;
    private boolean includeITM;
    private boolean includeValidation;

    public ConfigurableExportPlugin() {
        super();

        XMLConfiguration config = ConfigPlugins.getPluginConfig(title);
        config.setExpressionEngine(new XPathExpressionEngine());
        exportRootDirectory = config.getString("/exportFolder", null);
        useSubFolderPerProcess = config.getBoolean("/useSubFolderPerProcess", false);

        includeDerivate = config.getBoolean("/folder/includeDerivate", false);
        includeMaster = config.getBoolean("/folder/includeMaster", false);
        includeOcr = config.getBoolean("/folder/includeOcr", false);
        includeSource = config.getBoolean("/folder/includeSource", false);
        includeImport = config.getBoolean("/folder/includeImport", false);
        includeExort = config.getBoolean("/folder/includeExort", false);
        includeITM = config.getBoolean("/folder/includeITM", false);
        includeValidation = config.getBoolean("/folder/includeValidation", false);

    }

    @Override
    public boolean startExport(Process process, String inZielVerzeichnis)
            throws IOException, InterruptedException, WriteException, PreferencesException, DocStructHasNoTypeException,
            MetadataTypeNotAllowedException, ExportFileException, UghHelperException, SwapException, DAOException, TypeNotAllowedForParentException {

        if (StringUtils.isBlank(exportRootDirectory)) {
            Helper.setFehlerMeldung("No export folder configured");
            problems.add("No export folder configured");
            return false;
        }
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

        myPrefs = process.getRegelsatz().getPreferences();
        cp = new ConfigProjects(process.getProjekt().getTitel());

        // read metadata data
        Fileformat gdzfile;
        ExportFileformat newfile = MetadatenHelper.getExportFileformatByName(process.getProjekt().getFileFormatDmsExport(), process.getRegelsatz());
        try {
            gdzfile = process.readMetadataFile();

            newfile.setDigitalDocument(gdzfile.getDigitalDocument());
            gdzfile = newfile;

        } catch (Exception e) {
            Helper.setFehlerMeldung(Helper.getTranslation("exportError") + process.getTitel(), e);
            log.error("Export cancelled: ", e);
            problems.add("Export cancelled: " + e.getMessage());
            return false;
        }

        trimAllMetadata(gdzfile.getDigitalDocument().getLogicalDocStruct());

        Path destination;
        if (useSubFolderPerProcess) {
            destination = Paths.get(exportRootDirectory, process.getTitel());
        } else {
            destination = Paths.get(exportRootDirectory);
        }
        if (!Files.exists(destination)) {
            Files.createDirectories(destination);
        }
        // copy folder to destination
        if (derivateFolder != null && Files.exists(derivateFolder)) {
            StorageProvider.getInstance().copyDirectory(derivateFolder, Paths.get(destination.toString(), derivateFolder.getFileName().toString()));
        }
        if (masterFolder != null && Files.exists(masterFolder)) {
            StorageProvider.getInstance().copyDirectory(masterFolder, Paths.get(destination.toString(), masterFolder.getFileName().toString()));
        }
        if (ocrFolder != null && Files.exists(ocrFolder)) {
            List<Path> ocrData = StorageProvider.getInstance().listFiles(ocrFolder.toString());
            for (Path path : ocrData) {
                if (Files.isDirectory(path)) {
                    StorageProvider.getInstance().copyDirectory(path, Paths.get(destination.toString(), path.getFileName().toString()));
                } else {
                    StorageProvider.getInstance().copyFile(path, Paths.get(destination.toString(), path.getFileName().toString()));
                }
            }
        }
        if (sourceFolder != null && Files.exists(sourceFolder)) {
            StorageProvider.getInstance().copyDirectory(sourceFolder, Paths.get(destination.toString(), process.getTitel() + "_source"));
        }
        if (importFolder != null && Files.exists(importFolder)) {
            StorageProvider.getInstance().copyDirectory(importFolder, Paths.get(destination.toString(), process.getTitel() + "_import"));
        }
        if (exportFolder != null && Files.exists(exportFolder)) {
            StorageProvider.getInstance().copyDirectory(exportFolder, Paths.get(destination.toString(), process.getTitel() + "_export"));
        }
        if (itmFolder != null && Files.exists(itmFolder)) {
            StorageProvider.getInstance().copyDirectory(itmFolder, Paths.get(destination.toString(), itmFolder.getFileName().toString()));
        }
        if (derivateFolder != null && Files.exists(derivateFolder)) {
            StorageProvider.getInstance().copyDirectory(derivateFolder, Paths.get(destination.toString(), derivateFolder.getFileName().toString()));
        }
        if (validationFolder != null && Files.exists(validationFolder)) {
            StorageProvider.getInstance()
            .copyDirectory(validationFolder, Paths.get(destination.toString(), validationFolder.getFileName().toString()));
        }

        // write mets file
        Path metsFile = Paths.get(destination.toString(), process.getTitel() + ".xml");

        writeMetsFile(process, metsFile.toString(), gdzfile, false);

        Helper.setMeldung(null, process.getTitel() + ": ", "ExportFinished");

        return true;
    }

}
