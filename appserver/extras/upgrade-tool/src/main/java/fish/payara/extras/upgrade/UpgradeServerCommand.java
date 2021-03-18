/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2020-2021 Payara Foundation and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://github.com/payara/Payara/blob/master/LICENSE.txt
 * See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * The Payara Foundation designates this particular file as subject to the "Classpath"
 * exception as provided by the Payara Foundation in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package fish.payara.extras.upgrade;

import com.sun.enterprise.admin.cli.CLICommand;
import com.sun.enterprise.universal.i18n.LocalStringsImpl;
import com.sun.enterprise.universal.process.ProcessManagerException;
import com.sun.enterprise.util.OS;
import com.sun.enterprise.util.StringUtils;
import org.glassfish.api.ExecutionContext;
import org.glassfish.api.Param;
import org.glassfish.api.ParamDefaultCalculator;
import org.glassfish.api.admin.CommandException;
import org.glassfish.api.admin.CommandModel;
import org.glassfish.api.admin.CommandValidationException;
import org.glassfish.hk2.api.PerLookup;
import org.jvnet.hk2.annotations.Service;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Command to upgrade Payara server to a newer version
 *
 * @author Jonathan Coustick
 */
@Service(name = "upgrade-server")
@PerLookup
public class UpgradeServerCommand extends BaseUpgradeCommand {

    private static final String USE_DOWNLOADED_PARAM_NAME = "useDownloaded";
    private static final String USERNAME_PARAM_NAME = "username";
    private static final String NEXUS_PASSWORD_PARAM_NAME = "nexusPassword";
    private static final String VERSION_PARAM_NAME = "version";

    @Param(name = USERNAME_PARAM_NAME, optional = true)
    private String username;

    @Param(name = NEXUS_PASSWORD_PARAM_NAME, password = true, optional = true, alias = "nexuspassword")
    private String nexusPassword;

    @Param(defaultValue = "payara", acceptableValues = "payara, payara-ml, payara-web, payara-web-ml", optional = true)
    private String distribution;

    @Param(name = VERSION_PARAM_NAME, optional = true)
    private String version;

    @Param(name = "stage", optional = true, defaultCalculator = DefaultStageParamCalculator.class)
    private boolean stage;

    @Param(name = USE_DOWNLOADED_PARAM_NAME, optional = true, alias = "usedownloaded")
    private File useDownloadedFile;

    private static final String NEXUS_URL = System.getProperty("fish.payara.upgrade.repo.url",
            "https://nexus.payara.fish/repository/payara-enterprise/fish/payara/distributions/");
    private static final String ZIP = ".zip";

    private static final LocalStringsImpl strings = new LocalStringsImpl(CLICommand.class);

    @Override
    protected void prevalidate() throws CommandException {
        // Perform usual pre-validation; we don't want to skip it or alter it in anyway, we just want to add to it
        super.prevalidate();

        // If useDownloaded is present, check it's present. If it isn't, we need to pre-validate the download parameters
        // again with optional set to false so as to mimic a "conditional optional".
        // Note that we can't use the parameter variables here since CLICommand#inject() hasn't been called yet
        if (getOption(USE_DOWNLOADED_PARAM_NAME) != null) {
            if (!Paths.get(getOption(USE_DOWNLOADED_PARAM_NAME)).toFile().exists()) {
                throw new CommandValidationException("File specified does not exist: " + useDownloadedFile);
            }
        } else {
            if (getOption(USERNAME_PARAM_NAME) == null) {
                prevalidateParameter(USERNAME_PARAM_NAME);
            }

            if (getOption(VERSION_PARAM_NAME) == null) {
                prevalidateParameter(VERSION_PARAM_NAME);
            }

            if (getOption(NEXUS_PASSWORD_PARAM_NAME) == null) {
                prevalidatePasswordParameter(NEXUS_PASSWORD_PARAM_NAME);
            }
        }
    }

    /**
     * Adapted from method in parent class, namely {@link CLICommand#prevalidate()}
     *
     * @param parameterName
     * @throws CommandValidationException
     */
    private void prevalidateParameter(String parameterName) throws CommandValidationException {
        // if option isn't set, prompt for it (if interactive), otherwise throw an error
        if (programOpts.isInteractive()) {
            try {
                // Build the terminal if it isn't present
                buildTerminal();
                buildLineReader();

                // Prompt for it
                if (getOption(parameterName) == null && lineReader != null) {
                    String val = lineReader.readLine(strings.get("optionPrompt", parameterName.toLowerCase(Locale.ENGLISH)));
                    if (ok(val)) {
                        options.set(parameterName, val);
                    }
                }
                // if it's still not set, that's an error
                if (getOption(parameterName) == null) {
                    logger.log(Level.INFO, strings.get("missingOption", "--" + parameterName));
                    throw new CommandValidationException(strings.get("missingOptions", parameterName));
                }
            } finally {
                closeTerminal();
            }
        } else {
            throw new CommandValidationException(strings.get("missingOptions", parameterName));
        }
    }

    /**
     * Adapted from method in parent class, namely CLICommand#initializeCommandPassword
     *
     * @param passwordParameterName
     * @throws CommandValidationException
     */
    private void prevalidatePasswordParameter(String passwordParameterName) throws CommandValidationException {
        // Get the ParamModel
        CommandModel.ParamModel passwordParam = commandModel.getParameters()
                .stream().filter(paramModel -> paramModel.getName().equalsIgnoreCase(passwordParameterName))
                .findFirst().orElse(null);

        // Get the password
        char[] passwordChars = null;
        if (passwordParam != null) {
            passwordChars = getPassword(passwordParam.getName(), passwordParam.getLocalizedPrompt(),
                    passwordParam.getLocalizedPromptAgain(), true);
        }

        if (passwordChars == null) {
            // if not terse, provide more advice about what to do
            String msg;
            if (programOpts.isTerse()) {
                msg = strings.get("missingPassword", name, passwordParameterName);
            } else {
                msg = strings.get("missingPasswordAdvice", name, passwordParameterName);
            }

            throw new CommandValidationException(msg);
        }

        options.set(passwordParameterName, new String(passwordChars));
    }

    @Override
    protected void validate() throws CommandException {
        // Perform usual validation; we don't want to skip it or alter it in anyway, we just want to add to it
        super.validate();

        // Check that someone hasn't manually specified --stage=false, on Windows it should default to true since
        // in-place upgrades aren't supported
        if (OS.isWindows() && !stage) {
            throw new CommandValidationException("Non-staged upgrades are not supported on Windows.");
        }

        // Create property files
        createPropertiesFile();
        createBatFile();
    }

    /**
     * Creates the upgrade-tool.properties file expected to be used by the applyStagedUpgrade, cleanupUpgrade, and
     * rollbackUpgrade scripts. If a file is already present, it will be deleted.
     *
     * @throws CommandValidationException If there's an issue deleting or creating the properties file
     */
    private void createPropertiesFile() throws CommandValidationException {
        // Perform file separator substitution for Linux if required
        String[] folders = Arrays.copyOf(MOVEFOLDERS, MOVEFOLDERS.length);
        if (OS.isWindows()) {
            for (int i = 0; i < folders.length; i++) {
                folders[i] = folders[i].replace("\\", "/");
            }
        }

        // Delete existing property file if present
        Path upgradeToolPropertiesPath = Paths.get(glassfishDir, "config", "upgrade-tool.properties");
        try {
            Files.deleteIfExists(upgradeToolPropertiesPath);
        } catch (IOException ioException) {
            throw new CommandValidationException("Encountered an error trying to existing delete " +
                    "upgrade-tool.properties file:\n", ioException);
        }

        // Create new property file and populate
        try (BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(upgradeToolPropertiesPath.toFile()))) {
            // Add variable name expected by scripts
            bufferedWriter.append(PAYARA_UPGRADE_DIRS_PROP + "=");

            // Add the move folders, separated by commas
            bufferedWriter.append(String.join(",", folders));
        } catch (IOException ioException) {
            throw new CommandValidationException("Encountered an error trying to write upgrade-tool.properties file:\n",
                    ioException);
        }
    }

    /**
     * Creates the upgrade-tool.bat file expected to be used by the applyStagedUpgrade.bat, cleanupUpgrade.bat, and
     * rollbackUpgrade.bat scripts. If a file is already present, it will be deleted.
     *
     * @throws CommandValidationException If there's an issue deleting or creating the properties file
     */
    private void createBatFile() throws CommandValidationException {
        // Perform file separator substitution for Windows if required
        String[] folders = Arrays.copyOf(MOVEFOLDERS, MOVEFOLDERS.length);
        if (!OS.isWindows()) {
            for (int i = 0; i < folders.length; i++) {
                folders[i] = folders[i].replace("/", "\\");
            }
        }

        // Delete existing property file if present
        Path upgradeToolBatPath = Paths.get(glassfishDir, "config", "upgrade-tool.bat");
        try {
            Files.deleteIfExists(upgradeToolBatPath);
        } catch (IOException ioException) {
            throw new CommandValidationException("Encountered an error trying to existing delete " +
                    "upgrade-tool.bat file:\n", ioException);
        }

        // Create new property file and populate
        try (BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(upgradeToolBatPath.toFile()))) {
            // Add variable name expected by scripts
            bufferedWriter.append("SET " + PAYARA_UPGRADE_DIRS_PROP + "=");

            // Add the move folders, separated by commas
            bufferedWriter.append(String.join(",", folders));
        } catch (IOException ioException) {
            throw new CommandValidationException("Encountered an error trying to write upgrade-tool.bat file:\n",
                    ioException);
        }
    }

    @Override
    public int executeCommand() {
        String url = NEXUS_URL + distribution + "/" + version + "/" + distribution + "-" + version + ZIP;
        String basicAuthString = username + ":" + nexusPassword;
        String authBytes = "Basic " + Base64.getEncoder().encodeToString(basicAuthString.getBytes());

        Path unzippedDirectory = null;

        // Download and/or unzip payara distribution, aborting upgrade if this fails
        try {
            Path tempFile = Files.createTempFile("payara", ".zip");

            if (useDownloadedFile != null) {
                Files.copy(useDownloadedFile.toPath(), tempFile, StandardCopyOption.REPLACE_EXISTING);
            } else {
                LOGGER.log(Level.INFO, "Downloading new Payara version...");
                URL nexusUrl = new URL(url);
                HttpURLConnection connection = (HttpURLConnection) nexusUrl.openConnection();
                connection.setRequestProperty("Authorization", authBytes);

                int code = connection.getResponseCode();
                if (code != 200) {
                    LOGGER.log(Level.SEVERE, "Error connecting to server: {0}", code);
                    return ERROR;
                }

                Files.copy(connection.getInputStream(), tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            FileInputStream unzipFileStream = new FileInputStream(tempFile.toFile());
            unzippedDirectory = extractZipFile(unzipFileStream);
        } catch (IOException ioe) {
            LOGGER.log(Level.SEVERE, "Error preparing for upgrade, aborting upgrade: {0}", ioe);
            return ERROR;
        }
        if (unzippedDirectory == null) {
            LOGGER.log(Level.SEVERE, "Error preparing for upgrade, aborting upgrade: could not extract archive");
            return ERROR;
        }

        // Attempt to backup domains, exiting out if it fails
        try {
            backupDomains();
        } catch (CommandException ce) {
            LOGGER.log(Level.SEVERE, "Error executing backup-domain command, aborting upgrade: {0}", ce.toString());
            return ERROR;
        }

        try {
            cleanupExisting();
        } catch (IOException ioe) {
            LOGGER.log(Level.SEVERE, "Error cleaning up previous upgrades, aborting upgrade: {0}", ioe.toString());
            return ERROR;
        }

        try {
            moveFiles(unzippedDirectory);

            if (!OS.isWindows()) {
                fixPermissions();
            }
        } catch (IOException ex) {
            LOGGER.log(Level.SEVERE, "Error upgrading Payara Server, rolling back upgrade: {0}", ex.toString());

            try {
                if (stage) {
                    deleteStagedInstall();
                } else {
                    undoMoveFiles();
                }
            } catch (IOException ex1) {
                LOGGER.log(Level.WARNING, "Failed to restore previous state: {0}", ex.toString());
            }
            return ERROR;
        }

        // Don't update the nodes if we're staging, since we'll just be updating them with the "current" version
        if (!stage) {
            try {
                updateNodes();
            } catch (IOException ex) {
                // The IOException *should* be a MalformedURLException, since that's all updateNodes() currently throws
                LOGGER.log(Level.SEVERE, "Error upgrading Payara Server nodes, rolling back upgrade: {0}", ex.toString());
                try {
                    if (stage) {
                        deleteStagedInstall();
                    } else {
                        undoMoveFiles();
                    }
                } catch (IOException ex1) {
                    // Exit out here if we failed to restore, we don't want to push a broken install to the nodes
                    LOGGER.log(Level.SEVERE, "Failed to restore previous state of local install", ex1.toString());
                    return ERROR;
                }

                // MalformedURLException gets thrown before any command is run, so we don't need to reinstall the nodes
                return ERROR;
            }
        }

        if (stage) {
            LOGGER.log(Level.INFO,
                    "Upgrade successfully staged, please run the applyStagedUpgrade script to apply the upgrade.");
        }

        return SUCCESS;
    }

    private Path extractZipFile(InputStream remote) throws IOException {
        Path tempDirectory = Files.createTempDirectory("payara-new");

        try (ZipInputStream zipInput = new ZipInputStream(remote)) {
            ZipEntry entry = zipInput.getNextEntry();
            while (entry != null) {
                Path endPath = tempDirectory.resolve(entry.getName());
                if (entry.isDirectory()) {
                    endPath.toFile().mkdirs();
                } else {
                    try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(endPath.toFile()))) {
                        byte[] buffer = new byte[1024];
                        int length;
                        while ((length = zipInput.read(buffer)) != -1) {
                            out.write(buffer, 0, length);
                        }
                        out.flush();
                    }
                }
                entry = zipInput.getNextEntry();
            }
        }
        return tempDirectory;
    }

    private void backupDomains() throws CommandException {
        LOGGER.log(Level.INFO, "Backing up domain configs");
        File[] domaindirs = getDomainsDir().listFiles(File::isDirectory);
        for (File domaindir : domaindirs) {
            CLICommand backupDomainCommand = CLICommand.getCommand(habitat, "backup-domain");
            if (StringUtils.ok(domainDirParam)) {
                backupDomainCommand.execute("backup-domain", "--domaindir", domainDirParam, domaindir.getName());
            } else {
                backupDomainCommand.execute("backup-domain", domaindir.getName());
            }
        }
    }

    private void cleanupExisting() throws IOException {
        LOGGER.log(Level.FINE, "Deleting old server backup if present");
        DeleteFileVisitor visitor = new DeleteFileVisitor();
        for (String folder : MOVEFOLDERS) {
            Path folderPath = Paths.get(glassfishDir, folder + ".old");
            // Only attempt to delete folders which exist
            // Don't fail out if it doesn't exist, just keep going - we want to delete all we can
            if (folderPath.toFile().exists()) {
                Files.walkFileTree(folderPath, visitor);
            }
        }
        LOGGER.log(Level.FINE, "Deleted old server backup");
        deleteStagedInstall();
    }

    private void moveFiles(Path newVersion) throws IOException {
        if (!stage) {
            LOGGER.log(Level.FINE, "Moving files to old");
            for (String folder : MOVEFOLDERS) {
                try {
                    // Just attempt to move all folders - any exceptions aside from NoSuchFile on an MQ directory
                    // are unexpected and we should cancel out if we hit one
                    Files.move(Paths.get(glassfishDir, folder), Paths.get(glassfishDir, folder + ".old"),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (NoSuchFileException nsfe) {
                    // We can't nicely check if the "current" installation is a web distribution or not ("distribution"
                    // param is optional with "useDownloaded"), so just attempt to move all and specifically catch a
                    // NSFE for the MQ directory
                    if (nsfe.getMessage().contains(
                            "payara5" + File.separator + "glassfish" + File.separator + ".." + File.separator + "mq")) {
                        LOGGER.log(Level.FINE, "Ignoring NoSuchFileException for mq directory under assumption " +
                                "this is a payara-web distribution. Continuing to move files...");
                    } else {
                        throw nsfe;
                    }
                }
            }
        }
        LOGGER.log(Level.FINE, "Moved files to old");

        moveExtracted(newVersion);
    }

    private void moveExtracted(Path newVersion) throws IOException {
        LOGGER.log(Level.FINE, "Copying extracted files");

        for (String folder : MOVEFOLDERS) {
            Path sourcePath = newVersion.resolve("payara5" + File.separator + "glassfish" + File.separator + folder);
            Path targetPath = Paths.get(glassfishDir, folder);
            if (stage) {
                targetPath = Paths.get(targetPath + ".new");
            }

            if (Paths.get(glassfishDir, folder).toFile().isDirectory()) {
                if (!targetPath.toFile().exists()) {
                    Files.createDirectory(targetPath);
                }
            }

            CopyFileVisitor visitor = new CopyFileVisitor(sourcePath, targetPath);
            Files.walkFileTree(sourcePath, visitor);
        }
        LOGGER.log(Level.FINE, "Extracted files copied");
    }

    private void undoMoveFiles() throws IOException {
        // We don't know the state of the "current" or "old" installs, so we need to do this file by file with
        // a visitor that overwrites rather than doing it by folder with Files.move since Files.move would
        // require us to deal with DirectoryNotEmptyExceptions
        LOGGER.log(Level.FINE, "Moving old back");
        for (String folder : MOVEFOLDERS) {
            try {
                Path movedToPath = Paths.get(glassfishDir, folder + ".old");

                // Skip this folder if we don't appear to have moved it
                if (!movedToPath.toFile().exists()) {
                    continue;
                }

                // Copy the files from the folder, overwriting any
                Path movedFromPath = Paths.get(glassfishDir, folder);
                CopyFileVisitor copyVisitor = new CopyFileVisitor(movedToPath, movedFromPath);
                Files.walkFileTree(movedToPath, copyVisitor);

                // Clear out the leftover "old" install
                DeleteFileVisitor deleteVisitor = new DeleteFileVisitor();
                Files.walkFileTree(movedToPath, deleteVisitor);
            } catch (NoSuchFileException nsfe) {
                // Don't exit out on NoSuchFileExceptions, just keep going - any NoSuchFileException is likely
                // just a case of the file not having been moved yet.
                LOGGER.log(Level.FINE, "Ignoring NoSuchFileException for directory {0} under assumption " +
                        "it hasn't been moved yet. Continuing rollback...", folder + ".old");
            }
        }
        LOGGER.log(Level.FINE, "Moved old back");
    }

    private void fixPermissions() throws IOException {
        LOGGER.log(Level.FINE, "Fixing file permissions");
        // Fix the permissions of any bin directories in MOVEFOLDERS
        fixBinDirPermissions();
        // Fix the permissions of nadmin (since it's not in a bin directory)
        fixNadminPermissions();
        LOGGER.log(Level.FINE, "File permissions fixed");
    }

    private void fixBinDirPermissions() throws IOException {
        for (String folder : MOVEFOLDERS) {
            BinDirPermissionFileVisitor visitor = new BinDirPermissionFileVisitor();
            if (stage) {
                Files.walkFileTree(Paths.get(glassfishDir, folder + ".new"), visitor);
            } else {
                Files.walkFileTree(Paths.get(glassfishDir, folder), visitor);
            }
        }
    }

    private void fixNadminPermissions() throws IOException {
        // Check that we're actually upgrading the payara5/glassfish/lib directory before messing with permissions
        if (Arrays.stream(MOVEFOLDERS).anyMatch(folder -> folder.equals("lib"))) {
            Path nadminPath = Paths.get(glassfishDir, "lib", "nadmin");
            if (stage) {
                nadminPath = Paths.get(glassfishDir, "lib.new", "nadmin");
            }

            if (nadminPath.toFile().exists()) {
                Files.setPosixFilePermissions(nadminPath, PosixFilePermissions.fromString("rwxr-xr-x"));
            }

            Path nadminBatPath = Paths.get(glassfishDir, "lib", "nadmin.bat");
            if (stage) {
                nadminBatPath = Paths.get(glassfishDir, "lib.new", "nadmin.bat");
            }

            if (nadminBatPath.toFile().exists()) {
                Files.setPosixFilePermissions(nadminBatPath, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
        }
    }

    private class BinDirPermissionFileVisitor implements FileVisitor<Path> {

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            // Since MOVEDIRS only contains the top-level directory of what we want to upgrade (e.g. mq), checking
            // whether the name is equal to "bin" before skipping subtrees here is too heavy-handed
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            // If we're not in a bin directory, skip
            if (file.getParent().getFileName().toString().equals("bin") ||
                    file.getParent().getFileName().toString().equals("bin.new")) {

                if (!OS.isWindows()) {
                    LOGGER.log(Level.FINER, "Fixing file permissions for " + file.getFileName());
                    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
                }

                return FileVisitResult.CONTINUE;
            }

            return FileVisitResult.SKIP_SIBLINGS;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
            // We can't nicely check if the "new" installation is a web distribution or not ("distribution" param is
            // optional with "useDownloaded"), so specifically catch a NSFE for the MQ directory.
            if (exc instanceof NoSuchFileException && exc.getMessage().contains(
                    "payara5" + File.separator + "glassfish" + File.separator + ".." + File.separator + "mq")) {
                LOGGER.log(Level.FINE, "Ignoring NoSuchFileException for mq directory under assumption " +
                        "this is a payara-web distribution. Continuing fixing of permissions...");
                return FileVisitResult.SKIP_SUBTREE;
            }

            LOGGER.log(Level.SEVERE, "File could not visited: {0}", file.toString());
            throw exc;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
            return FileVisitResult.CONTINUE;
        }
    }

    /**
     * Class that calculates the default parameter value for --stage. To preserve the original functionality, the stage
     * param would have a default value of false. Since in-place upgrades aren't supported on Windows due to
     * file-locking, and so that a user doesn't have to always specify --stage=true if on Windows, this calculator makes
     * the default value true if on Windows.
     */
    public static class DefaultStageParamCalculator extends ParamDefaultCalculator {

        @Override
        public String defaultValue(ExecutionContext context) {
            return Boolean.toString(OS.isWindows());
        }
    }
}
