/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 *    Copyright (c) [2019] Payara Foundation and/or its affiliates. All rights reserved.
 * 
 *     The contents of this file are subject to the terms of either the GNU
 *     General Public License Version 2 only ("GPL") or the Common Development
 *     and Distribution License("CDDL") (collectively, the "License").  You
 *     may not use this file except in compliance with the License.  You can
 *     obtain a copy of the License at
 *     https://github.com/payara/Payara/blob/master/LICENSE.txt
 *     See the License for the specific
 *     language governing permissions and limitations under the License.
 * 
 *     When distributing the software, include this License Header Notice in each
 *     file and include the License file at glassfish/legal/LICENSE.txt.
 * 
 *     GPL Classpath Exception:
 *     The Payara Foundation designates this particular file as subject to the "Classpath"
 *     exception as provided by the Payara Foundation in the GPL Version 2 section of the License
 *     file that accompanied this code.
 * 
 *     Modifications:
 *     If applicable, add the following below the License Header, with the fields
 *     enclosed by brackets [] replaced by your own identifying information:
 *     "Portions Copyright [year] [name of copyright owner]"
 * 
 *     Contributor(s):
 *     If you wish your version of this file to be governed by only the CDDL or
 *     only the GPL Version 2, indicate your decision by adding "[Contributor]
 *     elects to include this software in this distribution under the [CDDL or GPL
 *     Version 2] license."  If you don't indicate a single choice of license, a
 *     recipient has the option to distribute your version of this file under
 *     either the CDDL, the GPL Version 2 or to extend the choice of license to
 *     its licensees as provided above.  However, if you add GPL Version 2 code
 *     and therefore, elected the GPL Version 2 license, then the option applies
 *     only if the new code is made subject to such option by the copyright
 *     holder.
 */
package fish.payara.microprofile;

import com.sun.enterprise.connectors.ConnectorRuntime;
import com.sun.enterprise.container.common.spi.util.ComponentEnvManager;
import com.sun.enterprise.deployment.Application;
import com.sun.enterprise.deployment.WebBundleDescriptor;
import org.glassfish.deployment.common.SecurityRoleMapper;
import org.glassfish.internal.api.Globals;
import org.glassfish.security.common.Group;
import org.glassfish.security.common.Role;

public class MicroProfileSecurityUtil {

    public static void setGroupRoleMapping(String[] roleNames, String[] groupNames) {
        ComponentEnvManager envManager = getComponentEnvManager();
        WebBundleDescriptor descriptor = (WebBundleDescriptor) envManager.getCurrentJndiNameEnvironment();
        Application application = descriptor.getApplication();
        if (application != null) {
            SecurityRoleMapper roleMapper = application.getRoleMapper();
            if (roleMapper != null) {
                for (int i = 0; i < roleNames.length; i++) {
                    roleMapper.assignRole(new Group(groupNames[i]), new Role(roleNames[i]), descriptor);
                }
            }
        }
    }

    private static ComponentEnvManager getComponentEnvManager() {
        try {
            return ConnectorRuntime.getRuntime().getComponentEnvManager();
        } catch (RuntimeException runtimeException) {
            // ConnectorRuntime.getRuntime() throws a RuntimeException if the service hasn't been started yet, so try
            // to start it by getting the service from service locator
            Globals.getDefaultBaseServiceLocator().getService(ConnectorRuntime.class);
            return ConnectorRuntime.getRuntime().getComponentEnvManager();
        }
    }
}
