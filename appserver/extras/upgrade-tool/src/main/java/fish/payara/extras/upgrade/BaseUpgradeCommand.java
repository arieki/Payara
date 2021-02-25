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
import com.sun.enterprise.util.SystemPropertyConstants;
import org.glassfish.api.admin.CommandException;
import org.glassfish.hk2.api.ServiceLocator;
import org.jvnet.hk2.config.ConfigParser;
import org.jvnet.hk2.config.DomDocument;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
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

    // Folders and files that are moved in the upgrade process
    // This will be converted to use Windows file separators if required during validate()
    protected static final String[] MOVEFOLDERS = {"common",
            "config" + File.separator + "branding",
            "h2db" + File.separator + "license.html",
            "h2db" + File.separator + "service",
            ".." + File.separator + "h2db" + File.separator + "license.html",
            ".." + File.separator + "h2db" + File.separator + "service",
            "legal",
            "modules",
            "osgi",
            "lib" + File.separator + "appclient",
            "lib" + File.separator + "appserv-rt.jar",
            "lib" + File.separator + "asadmin",
            "lib" + File.separator + "client",
            "lib" + File.separator + "deployment",
            "lib" + File.separator + "dtds",
            "lib" + File.separator + "embedded",
            "lib" + File.separator + "gf-client.jar",
            "lib" + File.separator + "grizzly-npn-api.jar",
            "lib" + File.separator + "grizzly-npn-bootstrap.jar",
            "lib" + File.separator + "install",
            "lib" + File.separator + "javaee.jar",
            "lib" + File.separator + "jndi-properties.jar",
            "lib" + File.separator + "monitor",
            "lib" + File.separator + "package-appclient.xml",
            "lib" + File.separator + "schemas",
            ".." + File.separator + "README.txt",
            ".." + File.separator + "LICENSE.txt",
            ".." + File.separator + "mq" + File.separator + "etc",
            ".." + File.separator + "mq" + File.separator + "examples",
            ".." + File.separator + "mq" + File.separator + "javadoc",
            ".." + File.separator + "mq" + File.separator + "legal",
            ".." + File.separator + "mq" + File.separator + "lib"};

    @Override
    protected void validate() throws CommandException {
        // Perform usual validation; we don't want to skip it or alter it in anyway, we just want to add to it.
        super.validate();

        // Set up the install dir variable
        glassfishDir = getInstallRootPath();
    }

    protected void updateNodes() throws MalformedURLException {
        File[] domaindirs = getDomainsDir().listFiles(File::isDirectory);
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
