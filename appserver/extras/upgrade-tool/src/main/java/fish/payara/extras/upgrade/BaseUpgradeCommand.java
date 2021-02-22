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
import com.sun.enterprise.admin.servermgmt.cli.LocalDomainCommand;
import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.config.serverbeans.Node;
import com.sun.enterprise.config.serverbeans.SshAuth;
import com.sun.enterprise.config.serverbeans.SshConnector;
import com.sun.enterprise.universal.process.ProcessManager;
import com.sun.enterprise.universal.process.ProcessManagerException;
import com.sun.enterprise.util.OS;
import com.sun.enterprise.util.StringUtils;
import com.sun.enterprise.util.SystemPropertyConstants;
import org.glassfish.api.admin.CommandException;
import org.glassfish.api.admin.CommandValidationException;
import org.glassfish.hk2.api.ServiceLocator;
import org.jvnet.hk2.config.ConfigParser;
import org.jvnet.hk2.config.DomDocument;

import javax.inject.Inject;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Base class containing shared methods and variables used by other upgrade/rollback commands.
 */
public abstract class BaseUpgradeCommand extends LocalDomainCommand {

    protected static final Logger LOGGER = Logger.getLogger(CLICommand.class.getPackage().getName());

    // The property present in the upgrade-tool properties file
    protected static final String PAYARA_UPGRADE_DIRS_PROP = "PAYARA_UPGRADE_DIRS";

    protected static final int DEFAULT_TIMEOUT_MSEC = 300000;

    protected String glassfishDir;

    @Inject
    protected ServiceLocator habitat;

    /**
     * Folders that are moved in the upgrade process - initialised in prepare with values from properties file
     */
    protected static String[] MOVEFOLDERS;

    @Override
    protected void validate() throws CommandException {
        // Perform usual validation; we don't want to skip it or alter it in anyway, we just want to add to it.
        super.validate();

        // Set up the install dir variable
        glassfishDir = getDomainsDir().getParent();

        // Initialise the MOVEFOLDERS variable
        initialiseMoveFolders();
    }

    /**
     * Initialises the MOVEFOLDERS variable using the upgrade-tool.properties file.
     * @throws CommandException If there's an issue reading from the properties file
     */
    private void initialiseMoveFolders() throws CommandException {
        // Read in properties file
        Properties properties = new Properties();
        try (InputStream input = new FileInputStream((Paths.get(glassfishDir, "config", "upgrade-tool.properties")).toFile())) {
            properties.load(input);
        } catch (IOException ioException) {
            throw new CommandValidationException("Error reading in properties file for upgrade tool: \n", ioException);
        }

        // Format property
        String moveDirsPropertyString = properties.getProperty(PAYARA_UPGRADE_DIRS_PROP);

        if (!StringUtils.ok(moveDirsPropertyString)) {
            throw new CommandValidationException("Error reading in expected fish.payara.extras.upgrade.moveDirs " +
                    "property from upgrade-tool.properties: property is not present or has no value.");
        }

        // Split on comma to make each item unique
        String[] splitMoveDirs =  moveDirsPropertyString.split(",");

        // Replace any file separators as required for Windows
        if (OS.isWindows()) {
            for (int i = 0; i < splitMoveDirs.length; i++) {
                splitMoveDirs[i] = splitMoveDirs[i].replace("/", "\\");
            }
        }

        // Store finished array in class variable
        MOVEFOLDERS = Arrays.copyOf(splitMoveDirs, splitMoveDirs.length);
    }

    protected void updateNodes() throws MalformedURLException {
        File[] domaindirs = Paths.get(glassfishDir, "domains").toFile().listFiles(File::isDirectory);
        for (File domaindir : domaindirs) {
            File domainXMLFile = Paths.get(domaindir.getAbsolutePath(), "config", "domain.xml").toFile();
            ConfigParser parser = new ConfigParser(habitat);
            try {
                parser.logUnrecognisedElements(false);
            } catch (NoSuchMethodError noSuchMethodError) {
                LOGGER.log(Level.FINE,
                        "Using a version of ConfigParser that does not support disabling log messages via method",
                        noSuchMethodError);
            }

            URL domainURL = domainXMLFile.toURI().toURL();
            DomDocument doc = parser.parse(domainURL);
            LOGGER.log(Level.INFO, "Updating nodes for domain " + domaindir.getName());
            for (Node node : doc.getRoot().createProxy(Domain.class).getNodes().getNode()) {
                if (node.getType().equals("SSH")) {
                    updateSSHNode(node);
                }
            }
        }
    }

    protected void updateSSHNode(Node node) {
        LOGGER.log(Level.INFO, "Updating ssh node {0}", new Object[]{node.getName()});
        ArrayList<String> command = new ArrayList<>();
        command.add(SystemPropertyConstants.getAdminScriptLocation(glassfishDir));
        command.add("--interactive=false");
        command.add("--passwordfile");
        command.add("-");

        command.add("install-node-ssh");
        command.add("--installdir");
        command.add(node.getInstallDir());

        command.add("--force"); //override files already there

        SshConnector sshConnector = node.getSshConnector();
        command.add("--sshport");
        command.add(sshConnector.getSshPort());
        SshAuth sshAuth = sshConnector.getSshAuth();
        command.add("--sshuser");
        command.add(sshAuth.getUserName());
        if (ok(sshAuth.getKeyfile())) {
            command.add("--sshkeyfile");
            command.add(sshAuth.getKeyfile());
        }

        command.add(node.getNodeHost());

        ProcessManager processManager = new ProcessManager(command);
        processManager.setStdinLines(getPasswords(sshAuth));
        processManager.setTimeoutMsec(DEFAULT_TIMEOUT_MSEC);
        processManager.setEcho(logger.isLoggable(Level.SEVERE));

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

    protected class DeleteFileVisitor implements FileVisitor<Path> {

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
