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
import com.sun.enterprise.util.OS;
import com.sun.enterprise.util.StringUtils;
import org.glassfish.api.admin.CommandException;
import org.glassfish.api.admin.CommandValidationException;
import org.glassfish.hk2.api.PerLookup;
import org.jvnet.hk2.annotations.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;

/**
 * Rolls back an upgrade
 *
 * @author Jonathan Coustick
 */
@Service(name = "rollback-server")
@PerLookup
public class RollbackUpgradeCommand extends BaseUpgradeCommand {

    @Override
    protected void validate() throws CommandException {
        // Perform usual validation; we don't want to skip it or alter it in anyway, we just want to add to it.
        super.validate();

        if (OS.isWindows()) {
            throw new CommandValidationException(
                    "Command not supported on Windows. Please use the rollbackUpgrade script.");
        }
    }

    @Override
    protected int executeCommand() {
        if (!Paths.get(glassfishDir, "modules.old").toFile().exists()) {
            LOGGER.log(Level.SEVERE, "No old version found to rollback");
            return ERROR;
        }

        LOGGER.log(Level.INFO, "Rolling back server...");

        // First up, remove any "staged" install
        try {
            deleteStagedInstall();
        } catch (IOException ioe) {
            LOGGER.log(Level.SEVERE, "Error cleaning up previous staged upgrade, aborting rollback: {0}",
                    ioe.toString());
            return ERROR;
        }

        // Second step, move "current" into "staged"
        try {
            LOGGER.log(Level.FINE, "Moving current install into a staged rollback directory");
            for (String file : MOVEFOLDERS) {
                try {
                    Files.move(Paths.get(glassfishDir, file), Paths.get(glassfishDir, file + ".new"),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (NoSuchFileException nsfe) {
                    // We can't nicely check if the current or old installation is a web distribution or not, so just
                    // attempt to move all and specifically catch a NSFE for the MQ directory
                    if (nsfe.getMessage().contains(
                            "payara5" + File.separator + "glassfish" + File.separator + ".." + File.separator + "mq")) {
                        LOGGER.log(Level.FINE, "Ignoring NoSuchFileException for mq directory under assumption " +
                                "this is a payara-web distribution. Continuing to move files...");
                    } else {
                        throw nsfe;
                    }
                }
            }
            LOGGER.log(Level.FINE, "Moved current install into a staged rollback directory");
        } catch (IOException ioe) {
            LOGGER.log(Level.SEVERE, "Error rolling back current install: {0}", ioe.toString());

            // Attempt to undo the rollback
            LOGGER.log(Level.INFO, "Attempting to undo rollback");
            try {
                moveStagedToCurrent();
            } catch (IOException ioe1) {
                LOGGER.log(Level.SEVERE, "Error undoing rollback: {0}", ioe1.toString());
            }

            return ERROR;
        }

        // Third step, move "old" into "current"
        try {
            for (String file : MOVEFOLDERS) {
                try {
                    Files.move(Paths.get(glassfishDir, file + ".old"), Paths.get(glassfishDir, file),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (NoSuchFileException nsfe) {
                    // We can't nicely check if the current or old installation is a web distribution or not, so just
                    // attempt to move all and specifically catch a NSFE for the MQ directory
                    if (nsfe.getMessage().contains(
                            "payara5" + File.separator + "glassfish" + File.separator + ".." + File.separator + "mq")) {
                        LOGGER.log(Level.FINE, "Ignoring NoSuchFileException for mq directory under assumption " +
                                "this is a payara-web distribution. Continuing to move files...");
                    } else {
                        throw nsfe;
                    }
                }
            }
        } catch (IOException ioe) {
            LOGGER.log(Level.SEVERE, "Error rolling back current install: {0}", ioe.toString());

            // Attempt to undo the rollback
            LOGGER.log(Level.INFO, "Attempting to undo rollback");

            // First up, move "current" back to "old"
            try {
                moveCurrentToOld();
            } catch (IOException ioe1) {
                LOGGER.log(Level.SEVERE, "Error undoing rollback: {0}", ioe1.toString());
                return ERROR;
            }

            // After moving "current" back to "old", move "staged" back to "current"
            try {
                moveStagedToCurrent();
            } catch (IOException ioe1) {
                LOGGER.log(Level.SEVERE, "Error undoing rollback: {0}", ioe1.toString());
            }

            return ERROR;
        }

        // Fourth step, roll back the nodes for all domains
        try {
            LOGGER.log(Level.INFO, "Rolling back nodes");
            reinstallNodes();
            LOGGER.log(Level.INFO, "Rolled back nodes");
        } catch (IOException ioe) {
            // The IOException *should* be a MalformedURLException, which occurs before an attempt to update the nodes
            // It gets thrown if the domain.xml couldn't be found, which implies something has gone wrong - rollback
            LOGGER.log(Level.SEVERE, "Error rolling back nodes: {0}", ioe.toString());

            // Attempt to undo the rollback
            LOGGER.log(Level.INFO, "Attempting to undo rollback");

            // First up, move "current" back to "old"
            try {
                moveCurrentToOld();
            } catch (IOException ioe1) {
                LOGGER.log(Level.SEVERE, "Error undoing rollback: {0}", ioe1.toString());
                return ERROR;
            }

            // After moving "current" back to "old", move "staged" back to "current"
            try {
                moveStagedToCurrent();
            } catch (IOException ioe1) {
                LOGGER.log(Level.SEVERE, "Error undoing rollback: {0}", ioe1.toString());
                return ERROR;
            }

            // MalformedURLException gets thrown before any command is run, so we don't need to reinstall the nodes
            return ERROR;
        } catch (CommandException ce) {
            // CommandException gets thrown once all nodes have been attempted to be rolled back and if at
            // least one roll back hit an error. We don't want to undo the roll back since the failure might be valid
            LOGGER.log(Level.WARNING, "Failed to roll back all nodes: inspect the logs from this command for " +
                            "the reasons. You can roll back the node installs individually using the " +
                            "rollback-server command on each node, or attempt to roll them all back again using the " +
                            "reinstall-nodes command. \n{0}",
                    ce.getMessage());
            return WARNING;


        }

        // Fifth step, remove "staged" again - if we've reached this point the install should have been successfully
        // rolled back so we don't need it anymore
        boolean logWarning = false;
        try {
            deleteStagedInstall();
        } catch (IOException ioe) {
            // Log the error, but we don't need to fail the command and exit out at this point
            LOGGER.log(Level.WARNING, "Error cleaning up rolled back upgrade: {0}", ioe.toString());
            logWarning = true;
        }

        // Final step, restore the original domain configs
        try {
            restoreDomains();
        } catch (CommandException ce) {
            LOGGER.log(Level.WARNING, "Error restore-domain command! " +
                    "Please restore your domain config manually. \n{0}", ce.toString());
            logWarning = true;
        }

        if (logWarning) {
            return WARNING;
        }

        return SUCCESS;
    }

    private void moveStagedToCurrent() throws IOException {
        LOGGER.log(Level.INFO, "Moving staged back to current");
        for (String file : MOVEFOLDERS) {
            Path stagedPath = Paths.get(glassfishDir, file + ".new");
            Path targetPath = Paths.get(glassfishDir, file);

            // Only move the stuff that exists - if it doesn't, it implies it was never moved to begin with
            if (!stagedPath.toFile().exists()) {
                continue;
            }

            // Use copy with overwrite since we don't know what state the move was in
            CopyFileVisitor copyFileVisitor = new CopyFileVisitor(stagedPath, targetPath);
            Files.walkFileTree(stagedPath, copyFileVisitor);
        }

        // Now delete
        deleteStagedInstall();

        LOGGER.log(Level.INFO, "Moved staged back to current");
    }

    private void moveCurrentToOld() throws IOException {
        LOGGER.log(Level.INFO, "Moving current install back to old");
        for (String file : MOVEFOLDERS) {
            Path currentPath = Paths.get(glassfishDir, file);
            Path targetPath = Paths.get(glassfishDir, file + ".old");

            // Only move the stuff that exists - if it doesn't, it implies it was never moved to begin with
            if (!currentPath.toFile().exists()) {
                continue;
            }

            // Use copy with overwrite since we don't know what state the move was in
            CopyFileVisitor copyFileVisitor = new CopyFileVisitor(currentPath, targetPath);
            Files.walkFileTree(currentPath, copyFileVisitor);
        }

        // Now delete
        deleteCurrentInstall();

        LOGGER.log(Level.INFO, "Moved current install back to old");
    }

    private void deleteCurrentInstall() throws IOException {
        DeleteFileVisitor visitor = new DeleteFileVisitor();
        for (String folder : MOVEFOLDERS) {
            // Only attempt to delete folders which exist
            // Don't fail out if it doesn't exist, just keep going - we want to delete all we can
            Path folderPath = Paths.get(glassfishDir, folder);
            if (folderPath.toFile().exists()) {
                Files.walkFileTree(folderPath, visitor);
            }
        }
    }

    private void restoreDomains() throws CommandException {
        LOGGER.log(Level.INFO, "Restoring domain configs");
        File[] domaindirs = getDomainsDir().listFiles(File::isDirectory);
        for (File domaindir : domaindirs) {
            CLICommand restoreDomainCommand = CLICommand.getCommand(habitat, "restore-domain");
            if (StringUtils.ok(domainDirParam)) {
                restoreDomainCommand.execute("restore-domain", "--domaindir", domainDirParam, domaindir.getName());
            } else {
                restoreDomainCommand.execute("restore-domain", domaindir.getName());
            }

        }
    }

}
