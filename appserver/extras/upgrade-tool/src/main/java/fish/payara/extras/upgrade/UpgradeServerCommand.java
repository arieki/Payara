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
import java.io.File;
import java.io.FileInputStream;
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
import java.util.Base64;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.sun.enterprise.util.OS;
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
        File[] domaindirs = Paths.get(glassfishDir, "domains").toFile().listFiles(File::isDirectory);
        for (File domaindir : domaindirs) {
            CLICommand backupDomainCommand = CLICommand.getCommand(habitat, "backup-domain");
            backupDomainCommand.execute("backup-domain", domaindir.getName());
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
