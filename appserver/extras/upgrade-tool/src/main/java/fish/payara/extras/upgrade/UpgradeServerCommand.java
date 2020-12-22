/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2020 Payara Foundation and/or its affiliates. All rights reserved.
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
import com.sun.enterprise.admin.servermgmt.cli.LocalDomainCommand;
import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.config.serverbeans.Node;
import com.sun.enterprise.config.serverbeans.SshAuth;
import com.sun.enterprise.config.serverbeans.SshConnector;
import com.sun.enterprise.universal.process.ProcessManager;
import com.sun.enterprise.universal.process.ProcessManagerException;
import com.sun.enterprise.util.SystemPropertyConstants;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.inject.Inject;

import org.glassfish.api.Param;
import org.glassfish.api.admin.CommandException;
import org.glassfish.hk2.api.PerLookup;
import org.glassfish.hk2.api.ServiceLocator;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.config.ConfigParser;;
import org.jvnet.hk2.config.DomDocument;

/**
 * Command to upgrade Payara server to a newer version
 * @author Jonathan Coustick
 */
@Service(name = "upgrade-server")
@PerLookup
public class UpgradeServerCommand extends LocalDomainCommand {
    
    private static final Logger LOGGER = Logger.getLogger(CLICommand.class.getPackage().getName());
    private static final int DEFAULT_TIMEOUT_MSEC = 300000;
    
    @Param
    private String username;
    
    @Param(password = true)
    private String nexusPassword;
    
    @Param(defaultValue="payara", acceptableValues = "payara, payara-ml, payara-web, payara-web-ml")
    private String distribution;
    
    @Param
    private String version;
    
    private static final String NEXUS_URL="https://nexus.payara.fish/repository/payara-enterprise/fish/payara/distributions/";
    private static final String ZIP = ".zip";
    
    private String glassfishDir;
    
    @Inject
    private ServiceLocator habitat;
    
    /** Folders that are moved in the upgrade process */
    private static final String[] moveFolders = {"/common", "/config/branding", "/h2db", "/legal", "/modules", "/osgi",
        "/lib/grizzly-npn-api.jar", "/lib/grizzly-npn-bootstrap.jar", "/lib/appclient", "/lib/client","/lib/deployment", "/lib/dtds", "/lib/embedded"
        ,"/lib/install", "/lib/monitor", "/lib/schemas" , "/../README.txt"};
    
    @Override
    public int executeCommand() throws CommandException {
        glassfishDir = getDomainsDir().getParent();
        
        
        String url = NEXUS_URL + distribution + "/" + version + "/" + distribution + "-" + version + ZIP;
        String basicAuthString = username + ":" + nexusPassword;
        String authBytes = "Basic " + Base64.getEncoder().encodeToString(basicAuthString.getBytes());
        
        
        try {
            URL nexusUrl = new URL(url);
            HttpURLConnection connection = (HttpURLConnection) nexusUrl.openConnection();
            connection.setRequestProperty("Authorization", authBytes);
            
            int code = connection.getResponseCode();
            if (code == 200) {
                
                //Path unzippedDirectory = extractZipFile(connection.getInputStream());
                
                Path tempFile = Files.createTempFile("payara", ".zip");
                Files.copy(connection.getInputStream(), tempFile, StandardCopyOption.REPLACE_EXISTING);
                
                FileInputStream unzipFileStream = new FileInputStream(tempFile.toFile());
                Path unzippedDirectory = extractZipFile(unzipFileStream);
                backupDomains();
                moveFiles();
                moveExtracted(unzippedDirectory);
                
                File domainXMLFile = getDomainXml();
                ConfigParser parser = new ConfigParser(habitat);
                URL domainURL = domainXMLFile.toURI().toURL();
                Logger configParserLogger = Logger.getLogger(ConfigParser.class.getName());
                Level oldConfigParserLogLevel = configParserLogger.getLevel();
                configParserLogger.setLevel(Level.FINE);
                DomDocument doc = parser.parse(domainURL);
                LOGGER.log(Level.SEVERE, "Upgrading remote nodes");
                for (Node node : doc.getRoot().createProxy(Domain.class).getNodes().getNode()) {
                    LOGGER.log(Level.SEVERE, "Upgrading remote node: {0}", node.getName());
                    if (node.getType().equals("SSH")) {
                        upgradeSSHNode(node, tempFile);
                    }
                }
                configParserLogger.setLevel(oldConfigParserLogLevel);
            } else {
                LOGGER.log(Level.SEVERE, "Error connecting to server: {0}", code);
                return ERROR;
            }
        } catch (IOException ex) {
            LOGGER.log(Level.SEVERE, "Error upgrading Payara Server", ex);
            ex.printStackTrace();
            try {
                undoMoveFiles();
            } catch (IOException ex1) {
                LOGGER.log(Level.WARNING, "Failed to restore previous state", ex1);
            }
            return ERROR;
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
    
    private void backupDomains() throws IOException {
        LOGGER.log(Level.FINE, "Backing up old domains");
        Files.copy(Paths.get(glassfishDir, "domains"), Paths.get(glassfishDir, "domains.old"), StandardCopyOption.REPLACE_EXISTING);
    }
    
    private void moveFiles() throws IOException {
        LOGGER.log(Level.FINE, "Deleting old backup");
        DeleteFileVisitor visitor = new DeleteFileVisitor();
        Path oldModules = Paths.get(glassfishDir, "/modules.old");
        if (oldModules.toFile().exists()) {
            for (String folder : moveFolders) {
                Files.walkFileTree(Paths.get(glassfishDir, folder + ".old"), visitor);
            }
            Files.walkFileTree(Paths.get(glassfishDir, "domains.old"), visitor);
        }
        LOGGER.log(Level.FINE, "Moving files to old");
        for (String folder : moveFolders) {
            Files.move(Paths.get(glassfishDir, folder), Paths.get(glassfishDir, folder + ".old"), StandardCopyOption.REPLACE_EXISTING);
        }
    }
    
    private void moveExtracted(Path newVersion) throws IOException {
        LOGGER.log(Level.FINE, "Moving extracted files");
        CopyFileVisitor visitor = new CopyFileVisitor(newVersion);
        for (String folder : moveFolders) {
            Files.walkFileTree(newVersion.resolve("payara5/glassfish" + folder), visitor);
        }
    }
    
    private void undoMoveFiles() throws IOException {
        LOGGER.log(Level.FINE, "Moving old back");
        for (String folder : moveFolders) {
            Files.move(Paths.get(glassfishDir, folder + ".old"), Paths.get(glassfishDir, folder), StandardCopyOption.REPLACE_EXISTING);
        }
        
    }
    
    private void upgradeSSHNode(Node remote, Path archiveFile) {
        LOGGER.log(Level.SEVERE, "Upgrading remote ssh node {0} with {1}", new Object[]{remote.getInstallDir(), archiveFile.toString()});
        ArrayList<String> command = new ArrayList<>();
        command.add(SystemPropertyConstants.getAdminScriptLocation(glassfishDir));
        
        command.add("install-node-ssh");
        command.add("--installdir");
        command.add(remote.getInstallDir());

        command.add("--force"); //override files already there
        command.add("--interactive=false");

        File archive = archiveFile.toFile();
        if (archive.exists() && archive.canRead()) {
            command.add("--archive");
            command.add(archiveFile.toString());
        }

        SshConnector sshConnector = remote.getSshConnector();
        command.add("--sshport");
        command.add(sshConnector.getSshPort());
        SshAuth sshAuth = sshConnector.getSshAuth();
        command.add("--sshuser");
        command.add(sshAuth.getUserName());
        if (ok(sshAuth.getKeyfile())) {
            command.add("--sshkeyfile");
            command.add(sshAuth.getKeyfile());
        }
        
        command.add("--passwordfile");
        command.add("-");
        
        command.add(remote.getNodeHost());

        StringBuilder out = new StringBuilder();
        
        ProcessManager processManager = new ProcessManager(command);
        processManager.setStdinLines(getPasswords(sshAuth));

        processManager.setTimeoutMsec(DEFAULT_TIMEOUT_MSEC);

        if (logger.isLoggable(Level.SEVERE)) {
            processManager.setEcho(true);
        } else {
            processManager.setEcho(false);
        }

        try {
            processManager.execute();
        } catch (ProcessManagerException ex) {
            logger.log(Level.SEVERE, "Error while executing command: {0}", ex.getMessage());
        }
    }
    
    protected List<String> getPasswords(SshAuth auth) {
        List<String> sshPasswords = new ArrayList<>();

        if (ok(auth.getPassword())) {
            sshPasswords.add("AS_ADMIN_SSHPASSWORD=" + auth.getPassword());
        }
        if (ok(auth.getKeyPassphrase())) {
            sshPasswords.add("AS_ADMIN_SSHKEYPASSPHRASE=" + auth.getKeyPassphrase());
        }

        return sshPasswords;
    }
    
    private class CopyFileVisitor implements FileVisitor<Path> {
        
        private final Path newVersionGlassfishDir;

        public CopyFileVisitor(Path newVersion) {
            this.newVersionGlassfishDir = newVersion.resolve("payara5/glassfish");
        }

        @Override
        public FileVisitResult preVisitDirectory(Path arg0, BasicFileAttributes arg1) throws IOException {
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path arg0, BasicFileAttributes arg1) throws IOException {
            Path resolved = Paths.get(glassfishDir).resolve(newVersionGlassfishDir.relativize(arg0));
            File parentDirFile = resolved.toFile().getParentFile();
            if (!parentDirFile.exists()) {
                parentDirFile.mkdirs();
            }
            
            Files.copy(arg0, resolved, StandardCopyOption.REPLACE_EXISTING);
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
    
    private class DeleteFileVisitor implements FileVisitor<Path> {

        @Override
        public FileVisitResult preVisitDirectory(Path arg0, BasicFileAttributes arg1) throws IOException {
                return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path arg0, BasicFileAttributes arg1) throws IOException {
            arg0.toFile().delete();
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path arg0, IOException arg1) throws IOException {
            LOGGER.log(Level.SEVERE, "File could not deleted: {0}", arg0.toString());
            throw arg1;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path arg0, IOException arg1) throws IOException {
            arg0.toFile().delete();
            return FileVisitResult.CONTINUE;
        }
        
    }
    
}
