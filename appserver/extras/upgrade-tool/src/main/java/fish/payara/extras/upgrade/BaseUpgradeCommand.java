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
import com.sun.enterprise.module.ModulesRegistry;
import com.sun.enterprise.module.single.StaticModulesRegistry;
import com.sun.enterprise.universal.process.ProcessManager;
import com.sun.enterprise.universal.process.ProcessManagerException;
import com.sun.enterprise.util.SystemPropertyConstants;
import org.glassfish.api.admin.CommandException;
import org.glassfish.hk2.api.MultiException;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.internal.api.Globals;
import org.jvnet.hk2.config.ConfigParser;
import org.jvnet.hk2.config.ConfigurationException;
import org.jvnet.hk2.config.DomDocument;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.AccessController;
import java.security.PrivilegedAction;
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
            "h2db",
            ".." + File.separator + "h2db",
            "legal",
            "modules",
            "osgi",
            "lib",
            ".." + File.separator + "README.txt",
            ".." + File.separator + "LICENSE.txt",
            ".." + File.separator + "mq",
            "bin",
            ".." + File.separator + "bin"};

    @Override
    protected void validate() throws CommandException {
        // Perform usual validation; we don't want to skip it or alter it in anyway, we just want to add to it.
        super.validate();

        // Set up the install dir variable
        glassfishDir = getInstallRootPath();
    }

    protected void reinstallNodes() throws IOException, CommandException, ConfigurationException {
        File[] domaindirs = getDomainsDir().listFiles(File::isDirectory);
        for (File domaindir : domaindirs) {
            File domainXMLFile = Paths.get(domaindir.getAbsolutePath(), "config", "domain.xml").toFile();

            // Don't use default habitat - since we're a CLI command it doesn't have a view of all the services
            // added via the modules directory
            ServiceLocator serviceLocator = createServiceLocator();
            ConfigParser parser = new ConfigParser(serviceLocator);
            try {
                parser.logUnrecognisedElements(false);
            } catch (NoSuchMethodError noSuchMethodError) {
                LOGGER.log(Level.FINE,
                        "Using a version of ConfigParser that does not support disabling log messages via method",
                        noSuchMethodError);
            }

            URL domainURL = domainXMLFile.toURI().toURL();
            DomDocument doc = parser.parse(domainURL);
            LOGGER.log(Level.INFO, "Reinstalling nodes for domain " + domaindir.getName());
            boolean throwException = false;
            List<String> failingNodes = new ArrayList<>();
            for (Node node : doc.getRoot().createProxy(Domain.class).getNodes().getNode()) {
                if (node.getType().equals("SSH")) {
                    boolean commandSuccess = reinstallSSHNode(node);
                    if (!commandSuccess) {
                        throwException = true;
                        failingNodes.add(node.getName());
                    }
                } else {
                    LOGGER.log(Level.WARNING, String.format("Only the SSH nodes are upgraded by this tool, " +
                                    "please upgrade your node with name %s of type %s manually", node.getName(),
                            node.getType()));
                }
            }

            if (throwException) {
                throw new CommandException("Error reinstalling nodes: " + String.join(", ", failingNodes));
            }
        }
    }

    private ServiceLocator createServiceLocator() throws CommandException {
        // Get the list of JAR files from the modules directory
        ArrayList<URL> urls = new ArrayList<>();
        for (File file : Paths.get(glassfishDir, "modules").toFile().listFiles()) {
            if (file.toString().endsWith(".jar")) {
                try {
                    urls.add(file.toURI().toURL());
                } catch (MalformedURLException malformedURLException) {
                    LOGGER.log(Level.SEVERE, "Error reading from modules directory "
                            + Paths.get(glassfishDir, "modules"));
                    throw new CommandException(malformedURLException);
                }
            }
        }

        ClassLoader cl = (ClassLoader) AccessController.doPrivileged(
                (PrivilegedAction) () -> new URLClassLoader(
                        urls.toArray(new URL[urls.size()]),
                        Globals.class.getClassLoader())
        );

        ModulesRegistry registry = new StaticModulesRegistry(cl);
        ServiceLocator serviceLocator;
        try {
            serviceLocator = registry.createServiceLocator("default");
        } catch (MultiException multiException) {
            LOGGER.log(Level.SEVERE, "Error creating service locator - something went wrong initialising " +
                    "service locator using modules directory " + Paths.get(glassfishDir, "modules"));
            throw new CommandException(multiException);
        }

        return serviceLocator;
    }

    protected boolean reinstallSSHNode(Node node) {
        LOGGER.log(Level.INFO, "Reinstalling SSH node {0}", new Object[]{node.getName()});
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

        boolean commandSuccess = true;
        try {
            processManager.execute();
            if (processManager.getStdout().contains("Command install-node-ssh failed")) {
                commandSuccess = false;
            }
        } catch (ProcessManagerException ex) {
            logger.log(Level.SEVERE, "Error while executing command: {0}", ex.getMessage());
        }

        return commandSuccess;
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

    protected void deleteStagedInstall() throws IOException {
        LOGGER.log(Level.FINE, "Deleting staged install if present");
        DeleteFileVisitor visitor = new DeleteFileVisitor();
        for (String folder : MOVEFOLDERS) {
            // Only attempt to delete folders which exist
            // Don't fail out if it doesn't exist, just keep going - we want to delete all we can
            Path folderPath = Paths.get(glassfishDir, folder + ".new");
            if (folderPath.toFile().exists()) {
                Files.walkFileTree(folderPath, visitor);
            }
        }
        LOGGER.log(Level.FINE, "Deleted staged install");
    }

    protected class CopyFileVisitor implements FileVisitor<Path> {

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
            // We can't nicely check if the "new" installation is a web distribution or not ("distribution" param is
            // optional with "useDownloaded"), so specifically catch a NSFE for the MQ directory.
            if (arg1 instanceof NoSuchFileException && arg1.getMessage().contains(
                    "payara5" + File.separator + "glassfish" + File.separator + ".." + File.separator + "mq")) {
                LOGGER.log(Level.FINE, "Ignoring NoSuchFileException for mq directory under assumption " +
                        "this is a payara-web distribution. Continuing copy...");
                return FileVisitResult.SKIP_SUBTREE;
            }

            LOGGER.log(Level.SEVERE, "File could not visited: {0}", arg0.toString());
            throw arg1;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path arg0, IOException arg1) throws IOException {
            return FileVisitResult.CONTINUE;
        }

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
            // Don't fail out on NSFE, just try to delete all of them
            if (arg1 instanceof NoSuchFileException) {
                LOGGER.log(Level.FINE, "Ignoring NoSuchFileException for directory {0} and continuing cleanup.",
                        arg0.toString());
                return FileVisitResult.SKIP_SUBTREE;
            }

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
