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

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.FileOutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Base64;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.sun.enterprise.util.OS;
import com.sun.enterprise.util.StringUtils;
import org.glassfish.api.ExecutionContext;
import org.glassfish.api.Param;
import org.glassfish.api.ParamDefaultCalculator;
import org.glassfish.api.admin.CommandException;
import org.glassfish.api.admin.CommandValidationException;
import org.glassfish.hk2.api.PerLookup;
import org.jvnet.hk2.annotations.Service;

/**
 * Command to upgrade Payara server to a newer version
 * @author Jonathan Coustick
 */
@Service(name = "upgrade-server")
@PerLookup
public class UpgradeServerCommand extends BaseUpgradeCommand {
    
    @Param
    private String username;
    
    @Param(password = true)
    private String nexusPassword;
    
    @Param(defaultValue="payara", acceptableValues = "payara, payara-ml, payara-web, payara-web-ml")
    private String distribution;
    
    @Param
    private String version;

    @Param(name = "stage", optional = true, defaultCalculator = DefaultStageParamCalculator.class)
    private boolean stage;

    private static final String NEXUS_URL = System.getProperty("fish.payara.upgrade.repo.url",
            "https://nexus.payara.fish/repository/payara-enterprise/fish/payara/distributions/");
    private static final String ZIP = ".zip";

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
        
        try {
            LOGGER.log(Level.INFO, "Downloading new Payara version...");
            URL nexusUrl = new URL(url);
            HttpURLConnection connection = (HttpURLConnection) nexusUrl.openConnection();
            connection.setRequestProperty("Authorization", authBytes);
            
            int code = connection.getResponseCode();
            if (code != 200) {
                LOGGER.log(Level.SEVERE, "Error connecting to server: {0}", code);
                return ERROR;
            }
                
            Path tempFile = Files.createTempFile("payara", ".zip");
            Files.copy(connection.getInputStream(), tempFile, StandardCopyOption.REPLACE_EXISTING);
            
            FileInputStream unzipFileStream = new FileInputStream(tempFile.toFile());
            Path unzippedDirectory = extractZipFile(unzipFileStream);

            try {
                backupDomains();
            } catch (CommandException ce) {
                LOGGER.log(Level.SEVERE, "Could not find backup-domain command, exiting...");
                ce.printStackTrace();
                return ERROR;
            }

            cleanupExisting();
            moveFiles(unzippedDirectory);

            if (!OS.isWindows()) {
                fixPermissions();
            }

            // Don't update the nodes if we're staging, since we'll just be updating them with the "current" version
            if (!stage) {
                updateNodes();
            }
        } catch (IOException ex) {
            LOGGER.log(Level.SEVERE, "Error upgrading Payara Server", ex);
            ex.printStackTrace();
            try {
                if (stage) {
                    deleteStagedInstall();
                } else {
                    undoMoveFiles();
                }
            } catch (IOException ex1) {
                LOGGER.log(Level.WARNING, "Failed to restore previous state", ex1);
            }
            return ERROR;
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
                    try ( BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(endPath.toFile()))) {
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
    
    private void backupDomains() throws IOException, CommandException {
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
        Path oldModules = Paths.get(glassfishDir, "modules.old");
        if (oldModules.toFile().exists()) {
            for (String folder : MOVEFOLDERS) {
                Files.walkFileTree(Paths.get(glassfishDir, folder + ".old"), visitor);
            }
        }
        deleteStagedInstall();
    }

    private void deleteStagedInstall() throws IOException {
        LOGGER.log(Level.FINE, "Deleting staged install if present");
        DeleteFileVisitor visitor = new DeleteFileVisitor();
        Path newModules = Paths.get(glassfishDir, "modules.new");
        if (newModules.toFile().exists()) {
            for (String folder : MOVEFOLDERS) {
                Files.walkFileTree(Paths.get(glassfishDir, folder + ".new"), visitor);
            }
        }
    }

    private void moveFiles(Path newVersion) throws IOException {
        if (!stage) {
            LOGGER.log(Level.FINE, "Moving files to old");
            for (String folder : MOVEFOLDERS) {
                Files.move(Paths.get(glassfishDir, folder), Paths.get(glassfishDir, folder + ".old"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }

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
    }
    
    private void undoMoveFiles() throws IOException {
        LOGGER.log(Level.FINE, "Moving old back");
        for (String folder : MOVEFOLDERS) {
            Files.move(Paths.get(glassfishDir, folder + ".old"), Paths.get(glassfishDir, folder), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void fixPermissions() throws IOException {
        LOGGER.log(Level.FINE, "Fixing file permissions");
        // Fix the permissions of any bin directories in MOVEFOLDERS
        fixBinDirPermissions();
        // Fix the permissions of nadmin (since it's not in a bin directory)
        fixNadminPermissions();
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

    private class CopyFileVisitor implements FileVisitor<Path> {
        
        private final Path sourcePath;
        private final Path targetPath;

        public CopyFileVisitor(Path sourcePath, Path targetPath) {
            this.sourcePath = sourcePath;
            this.targetPath = targetPath;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path arg0, BasicFileAttributes arg1) throws IOException {
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path arg0, BasicFileAttributes arg1) throws IOException {
            Path resolvedPath = targetPath.resolve(sourcePath.relativize(arg0));

            File parentFile = resolvedPath.toFile().getParentFile();
            if (!parentFile.exists()) {
                parentFile.mkdirs();
            }

            Files.copy(arg0, resolvedPath, StandardCopyOption.REPLACE_EXISTING);

            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path arg0, IOException arg1) throws IOException {
            LOGGER.log(Level.SEVERE, "File could not visited: {0}", arg0.toString());
            throw arg1;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path arg0, IOException arg1) throws IOException {
            return FileVisitResult.CONTINUE;
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
            if (!file.getParent().getFileName().toString().equals("bin")) {
                return FileVisitResult.SKIP_SIBLINGS;
            }

            if (!OS.isWindows()) {
                LOGGER.log(Level.FINER, "Fixing file permissions for " + file.getFileName());
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
            }

            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
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
