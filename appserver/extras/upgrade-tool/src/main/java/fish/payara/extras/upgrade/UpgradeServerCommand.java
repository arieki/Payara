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
import java.io.BufferedOutputStream;
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
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.glassfish.api.Param;
import org.glassfish.api.admin.CommandException;
import org.glassfish.hk2.api.PerLookup;
import org.jvnet.hk2.annotations.Service;

/**
 * Command to upgrade Payara server to a newer version
 * @author jonathan coustick
 */
@Service(name = "upgrade-server")
@PerLookup
public class UpgradeServerCommand extends LocalDomainCommand {
    
    private static final Logger LOGGER = Logger.getLogger(CLICommand.class.getPackage().getName());
    
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
                moveFiles();
                Path unzippedDirectory = extractZipFile(connection.getInputStream());
                moveExtracted(unzippedDirectory);
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
                ex1.printStackTrace();
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
                System.out.println(endPath.toString());
                if (entry.isDirectory()) {
                    endPath.toFile().mkdirs();
                } else {
                    try ( BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(endPath.toFile()))) {
                        byte[] buffer = new byte[1024];
                        while (zipInput.read(buffer) != -1) {
                            out.write(buffer);
                        }
                    }
                }
                entry = zipInput.getNextEntry();
            }
        }
        return tempDirectory;
    }
    
    private void moveFiles() throws IOException {
        Files.move(Paths.get(glassfishDir, "/modules"), Paths.get(glassfishDir, "/modules.old")); 
        Files.move(Paths.get(glassfishDir, "/config/branding"), Paths.get(glassfishDir, "/config/branding.old")); 
        Files.move(Paths.get(glassfishDir, "/legal"), Paths.get(glassfishDir, "/legal.old")); 
        Files.move(Paths.get(glassfishDir, "/h2db"), Paths.get(glassfishDir, "/h2db.old")); 
        Files.move(Paths.get(glassfishDir, "/osgi"), Paths.get(glassfishDir, "/osgi.old")); 
        Files.move(Paths.get(glassfishDir, "/common"), Paths.get(glassfishDir, "/common.old")); 
    }
    
    private void moveExtracted(Path newVersion) throws IOException {
        CopyFileVisitor visitor = new CopyFileVisitor(newVersion);
        Files.walkFileTree(newVersion.resolve("payara5/glassfish/modules"), visitor);
        Files.walkFileTree(newVersion.resolve("payara5/glassfish/config/branding"), visitor);
        Files.walkFileTree(newVersion.resolve("payara5/glassfish/legal"), visitor);
        Files.walkFileTree(newVersion.resolve("payara5/glassfish/h2db"), visitor);
        Files.walkFileTree(newVersion.resolve("payara5/glassfish/osgi"), visitor);
        Files.walkFileTree(newVersion.resolve("payara5/glassfish/common"), visitor);
    }
    
    private void undoMoveFiles() throws IOException {
        Files.move(Paths.get(glassfishDir, "/modules.old"), Paths.get(glassfishDir, "/modules"));
        Files.move(Paths.get(glassfishDir, "/config/branding.old"), Paths.get(glassfishDir, "/config/branding"));
        Files.move(Paths.get(glassfishDir, "/legal.old"), Paths.get(glassfishDir, "/legal"));
        Files.move(Paths.get(glassfishDir, "/h2db.old"), Paths.get(glassfishDir, "/h2db"));
        Files.move(Paths.get(glassfishDir, "/osgi.old"), Paths.get(glassfishDir, "/osgi"));
        Files.move(Paths.get(glassfishDir, "/common.old"), Paths.get(glassfishDir, "/common"));
    }
    
    
    private class CopyFileVisitor implements FileVisitor<Path> {
        
        private Path newVersionGlassfishDir;

        public CopyFileVisitor(Path newVersion) {
            this.newVersionGlassfishDir = newVersion.resolve("payara5/glassfish");
        }

        @Override
        public FileVisitResult preVisitDirectory(Path arg0, BasicFileAttributes arg1) throws IOException {
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path arg0, BasicFileAttributes arg1) throws IOException {
            System.out.println(arg0.toString());
            Files.copy(arg0, Paths.get(glassfishDir).resolve(newVersionGlassfishDir.relativize(arg0)));
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
    
}
