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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;

/**
 * Rolls back an upgrade
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
        try {
            if (!Paths.get(glassfishDir, "modules.old").toFile().exists()) {
                LOGGER.log(Level.SEVERE, "No old version found to rollback");
                return ERROR;
            }

            DeleteFileVisitor visitor = new DeleteFileVisitor();
            LOGGER.log(Level.INFO, "Rolling back server...");
            for (String file : MOVEFOLDERS) {
                try {
                    Files.walkFileTree(Paths.get(glassfishDir, file), visitor);
                    Files.move(Paths.get(glassfishDir, file + ".old"), Paths.get(glassfishDir, file),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (NoSuchFileException nsfe) {
                    // We can't nicely check if the current or old installation is a web distribution or not, so just
                    // attempt to move all and specifically catch a FNFE for the MQ directory
                    if (nsfe.getMessage().contains(
                            "payara5" + File.separator + "glassfish" + File.separator + ".." + File.separator + "mq")) {
                        LOGGER.log(Level.FINER, "Ignoring NoSuchFileException for mq directory under assumption " +
                                "this is a payara-web distribution.");
                    } else {
                        throw nsfe;
                    }
                }
            }

            // Roll back the nodes for all domains
            updateNodes();

            // Restore the original domain configs
            try {
                restoreDomains();
            } catch (CommandException ce) {
                LOGGER.log(Level.SEVERE, "Could not find restore-domain command! " +
                        "Please restore your domain config manually.");
                ce.printStackTrace();
                return WARNING;
            }

            return SUCCESS;
        } catch (IOException ex) {
            LOGGER.log(Level.SEVERE, "Error restoring Payara Server", ex);
            ex.printStackTrace();
            return ERROR;
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
