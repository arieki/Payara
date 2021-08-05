/*
 *  DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 *  Copyright (c) 2021 Payara Foundation and/or its affiliates. All rights reserved.
 *
 *  The contents of this file are subject to the terms of either the GNU
 *  General Public License Version 2 only ("GPL") or the Common Development
 *  and Distribution License("CDDL") (collectively, the "License").  You
 *  may not use this file except in compliance with the License.  You can
 *  obtain a copy of the License at
 *  https://github.com/payara/Payara/blob/master/LICENSE.txt
 *  See the License for the specific
 *  language governing permissions and limitations under the License.
 *
 *  When distributing the software, include this License Header Notice in each
 *  file and include the License.
 *
 *  When distributing the software, include this License Header Notice in each
 *  file and include the License file at glassfish/legal/LICENSE.txt.
 *
 *  GPL Classpath Exception:
 *  The Payara Foundation designates this particular file as subject to the "Classpath"
 *  exception as provided by the Payara Foundation in the GPL Version 2 section of the License
 *  file that accompanied this code.
 *
 *  Modifications:
 *  If applicable, add the following below the License Header, with the fields
 *  enclosed by brackets [] replaced by your own identifying information:
 *  "Portions Copyright [year] [name of copyright owner]"
 *
 *  Contributor(s):
 *  If you wish your version of this file to be governed by only the CDDL or
 *  only the GPL Version 2, indicate your decision by adding "[Contributor]
 *  elects to include this software in this distribution under the [CDDL or GPL
 *  Version 2] license."  If you don't indicate a single choice of license, a
 *  recipient has the option to distribute your version of this file under
 *  either the CDDL, the GPL Version 2 or to extend the choice of license to
 *  its licensees as provided above.  However, if you add GPL Version 2 code
 *  and therefore, elected the GPL Version 2 license, then the option applies
 *  only if the new code is made subject to such option by the copyright
 *  holder.
 */

package fish.payara.microprofile.healthcheck.compat;

import com.sun.enterprise.config.serverbeans.Config;
import com.sun.enterprise.config.serverbeans.Configs;
import fish.payara.microprofile.healthcheck.config.MicroprofileHealthCheckConfiguration;
import org.glassfish.api.StartupRunLevel;
import org.glassfish.api.admin.config.ConfigurationUpgrade;
import org.glassfish.hk2.runlevel.RunLevel;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.config.ConfigSupport;
import org.jvnet.hk2.config.TransactionFailure;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.util.logging.Level;
import java.util.logging.Logger;

import static fish.payara.microprofile.Constants.DEFAULT_GROUP_NAME;

@Service
@RunLevel(StartupRunLevel.VAL)
public class MicroProfileHealthCheckUpgradeService implements ConfigurationUpgrade {

    @Inject
    private Configs configs;

    @Inject
    private Logger logger;

    @PostConstruct
    public void postConstruct() {
        for (Config config : configs.getConfig()) {
            // Get the old config bean
            MetricsHealthCheckConfiguration oldHealthCheckConfiguration = config.getExtensionByType(
                    MetricsHealthCheckConfiguration.class);

            // If no old healthcheck config found, just move on to the next config
            if (oldHealthCheckConfiguration == null) {
                continue;
            }

            // Get the new config bean
            MicroprofileHealthCheckConfiguration newHealthCheckConfiguration = config.getExtensionByType(
                    MicroprofileHealthCheckConfiguration.class);

            // If we don't have any new config - something has gone wrong
            if (newHealthCheckConfiguration == null) {
                logger.log(Level.WARNING, "Failed to upgrade legacy MicroProfile Health configuration for config: " +
                        config.getName());
                continue;
            }

            // Do the upgrade
            upgradeHealthcheckConfig(oldHealthCheckConfiguration, newHealthCheckConfiguration);
        }
    }

    /**
     * Upgrades the old {@link MetricsHealthCheckConfiguration} configuration to the new
     * {@link MicroprofileHealthCheckConfiguration}. Newer configuration is preferred to any old in case of conflicts.
     *
     * @param oldHealthCheckConfiguration
     * @param newHealthCheckConfiguration
     */
    private void upgradeHealthcheckConfig(MetricsHealthCheckConfiguration oldHealthCheckConfiguration,
            MicroprofileHealthCheckConfiguration newHealthCheckConfiguration) {

        // Migrate enabled
        if (oldHealthCheckConfiguration.getEnabled().equalsIgnoreCase("false")) {
            if (newHealthCheckConfiguration.getEnabled().equalsIgnoreCase("true")) {
                logger.log(Level.INFO, "Legacy configuration detected for MicroProfile Health enabled attribute, " +
                        "updating the new config to match old");
                try {
                    ConfigSupport.apply(newHealthCheckConfigurationProxy -> {
                        newHealthCheckConfigurationProxy.setEnabled("false");
                        return newHealthCheckConfigurationProxy;
                    }, newHealthCheckConfiguration);
                } catch (TransactionFailure transactionFailure) {
                    logger.log(Level.WARNING, "Failed to upgrade legacy MicroProfile Health configuration",
                            transactionFailure);
                }
            }

            // Set the old config back to default so we don't attempt this each startup
            logger.log(Level.FINE, "Removing legacy configuration MicroProfile Health enabled attribute");
            try {
                ConfigSupport.apply(oldHealthCheckConfigurationProxy -> {
                    oldHealthCheckConfigurationProxy.setEnabled("true");
                    return oldHealthCheckConfigurationProxy;
                }, oldHealthCheckConfiguration);
            } catch (TransactionFailure transactionFailure) {
                logger.log(Level.WARNING, "Failed to remove legacy MicroProfile Health configuration",
                        transactionFailure);
            }
        }

        if (!oldHealthCheckConfiguration.getEndpoint().equals("health")) {
            // Check if the new config has a non-default value
            if (newHealthCheckConfiguration.getEndpoint().equals("health")) {
                logger.log(Level.INFO, "Legacy configuration detected for MicroProfile Health endpoint attribute, " +
                        "updating the new config to match old");
                try {
                    ConfigSupport.apply(newHealthCheckConfigurationProxy -> {
                        newHealthCheckConfigurationProxy.setEndpoint(oldHealthCheckConfiguration.getEndpoint());
                        return newHealthCheckConfigurationProxy;
                    }, newHealthCheckConfiguration);

                    // Set the old config back to default
                    logger.log(Level.FINE, "Removing legacy configuration MicroProfile Health endpoint attribute");
                    try {
                        ConfigSupport.apply(oldHealthCheckConfigurationProxy -> {
                            oldHealthCheckConfigurationProxy.setEndpoint("health");
                            return oldHealthCheckConfigurationProxy;
                        }, oldHealthCheckConfiguration);
                    } catch (TransactionFailure transactionFailure) {
                        logger.log(Level.WARNING, "Failed to remove legacy MicroProfile Health configuration",
                                transactionFailure);
                    }
                } catch (TransactionFailure transactionFailure) {
                    logger.log(Level.WARNING, "Failed to upgrade legacy MicroProfile Health configuration",
                            transactionFailure);
                }
            } else {
                // New config doesn't have a non-default value to override: just set the old config back to default
                // so we don't attempt this every startup
                logger.log(Level.INFO,
                        "Legacy configuration detected for MicroProfile Health endpoint attribute. " +
                                "There is a non-default value currently set for this attribute in the " +
                                "newer config: new config will not be updated with value from the old config");
                logger.log(Level.FINE, "Removing legacy configuration MicroProfile Health endpoint attribute");
                try {
                    ConfigSupport.apply(oldHealthCheckConfigurationProxy -> {
                        oldHealthCheckConfigurationProxy.setEndpoint("health");
                        return oldHealthCheckConfigurationProxy;
                    }, oldHealthCheckConfiguration);
                } catch (TransactionFailure transactionFailure) {
                    logger.log(Level.WARNING, "Failed to remove legacy MicroProfile Health configuration",
                            transactionFailure);
                }
            }
        }

        if (!oldHealthCheckConfiguration.getVirtualServers().equals("")) {
            if (newHealthCheckConfiguration.getVirtualServers().equals("")) {
                logger.log(Level.INFO,
                        "Legacy configuration detected for MicroProfile Health virtual-servers attribute, " +
                                "updating the new config to match old");
                try {
                    ConfigSupport.apply(newHealthCheckConfigurationProxy -> {
                        newHealthCheckConfigurationProxy.setVirtualServers(
                                oldHealthCheckConfiguration.getVirtualServers());
                        return newHealthCheckConfigurationProxy;
                    }, newHealthCheckConfiguration);

                    // Set the old config back to default
                    logger.log(Level.FINE,
                            "Removing legacy configuration MicroProfile Health virtual-servers attribute");
                    try {
                        ConfigSupport.apply(oldHealthCheckConfigurationProxy -> {
                            oldHealthCheckConfigurationProxy.setVirtualServers("");
                            return oldHealthCheckConfigurationProxy;
                        }, oldHealthCheckConfiguration);
                    } catch (TransactionFailure transactionFailure) {
                        logger.log(Level.WARNING, "Failed to remove legacy MicroProfile Health configuration",
                                transactionFailure);
                    }
                } catch (TransactionFailure transactionFailure) {
                    logger.log(Level.WARNING, "Failed to upgrade legacy MicroProfile Health configuration",
                            transactionFailure);
                }
            } else {
                // New config doesn't have a non-default value to override: just set the old config back to default
                // so we don't attempt this every startup
                logger.log(Level.INFO,
                        "Legacy configuration detected for MicroProfile Health virtual-servers attribute. " +
                                "There is a non-default value currently set for this attribute in the " +
                                "newer config: new config will not be updated with value from the old config");
                logger.log(Level.FINE, "Removing legacy configuration MicroProfile Health virtual-servers attribute");
                try {
                    ConfigSupport.apply(oldHealthCheckConfigurationProxy -> {
                        oldHealthCheckConfigurationProxy.setVirtualServers("");
                        return oldHealthCheckConfigurationProxy;
                    }, oldHealthCheckConfiguration);
                } catch (TransactionFailure transactionFailure) {
                    logger.log(Level.WARNING, "Failed to remove legacy MicroProfile Health configuration",
                            transactionFailure);
                }
            }
        }

        if (oldHealthCheckConfiguration.getSecurityEnabled().equalsIgnoreCase("true")) {
            if (newHealthCheckConfiguration.getSecurityEnabled().equalsIgnoreCase("false")) {
                logger.log(Level.INFO,
                        "Legacy configuration detected for MicroProfile Health security-enabled attribute, " +
                                "updating the new config to match old");
                try {
                    ConfigSupport.apply(newHealthCheckConfigurationProxy -> {
                        newHealthCheckConfigurationProxy.setSecurityEnabled(
                                oldHealthCheckConfiguration.getSecurityEnabled());
                        return newHealthCheckConfigurationProxy;
                    }, newHealthCheckConfiguration);
                } catch (TransactionFailure transactionFailure) {
                    logger.log(Level.WARNING, "Failed to upgrade legacy MicroProfile Health configuration",
                            transactionFailure);
                }
            }

            // Set the old config back to default so we don't attempt this each startup
            logger.log(Level.FINE, "Removing legacy configuration MicroProfile Health security-enabled attribute");
            try {
                ConfigSupport.apply(oldHealthCheckConfigurationProxy -> {
                    oldHealthCheckConfigurationProxy.setSecurityEnabled("false");
                    return oldHealthCheckConfigurationProxy;
                }, oldHealthCheckConfiguration);
            } catch (TransactionFailure transactionFailure) {
                logger.log(Level.WARNING, "Failed to remove legacy MicroProfile Health configuration",
                        transactionFailure);
            }
        }

        if (!oldHealthCheckConfiguration.getRoles().equals(DEFAULT_GROUP_NAME)) {
            if (newHealthCheckConfiguration.getRoles().equals(DEFAULT_GROUP_NAME)) {
                logger.log(Level.INFO, "Legacy configuration detected for MicroProfile Health roles attribute, " +
                        "updating the new config to match old");
                try {
                    ConfigSupport.apply(newHealthCheckConfigurationProxy -> {
                        newHealthCheckConfigurationProxy.setRoles(
                                oldHealthCheckConfiguration.getRoles());
                        return newHealthCheckConfigurationProxy;
                    }, newHealthCheckConfiguration);

                    // Set the old config back to default
                    logger.log(Level.FINE, "Removing legacy configuration MicroProfile Health roles attribute");
                    try {
                        ConfigSupport.apply(oldHealthCheckConfigurationProxy -> {
                            oldHealthCheckConfigurationProxy.setRoles(DEFAULT_GROUP_NAME);
                            return oldHealthCheckConfigurationProxy;
                        }, oldHealthCheckConfiguration);
                    } catch (TransactionFailure transactionFailure) {
                        logger.log(Level.WARNING, "Failed to remove legacy MicroProfile Health configuration",
                                transactionFailure);
                    }
                } catch (TransactionFailure transactionFailure) {
                    logger.log(Level.WARNING, "Failed to upgrade legacy MicroProfile Health configuration",
                            transactionFailure);
                }
            } else {
                // New config doesn't have a non-default value to override: just set the old config back to default
                // so we don't attempt this every startup
                logger.log(Level.INFO,
                        "Legacy configuration detected for MicroProfile Health roles attribute. " +
                                "There is a non-default value currently set for this attribute in the " +
                                "newer config: new config will not be updated with value from the old config");
                logger.log(Level.FINE, "Removing legacy configuration MicroProfile Health roles attribute");
                try {
                    ConfigSupport.apply(oldHealthCheckConfigurationProxy -> {
                        oldHealthCheckConfigurationProxy.setRoles(DEFAULT_GROUP_NAME);
                        return oldHealthCheckConfigurationProxy;
                    }, oldHealthCheckConfiguration);
                } catch (TransactionFailure transactionFailure) {
                    logger.log(Level.WARNING, "Failed to remove legacy MicroProfile Health configuration",
                            transactionFailure);
                }
            }
        }

    }

}