/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) [2021] Payara Foundation and/or its affiliates. All rights reserved.
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
import junit.framework.TestCase;
import org.glassfish.api.admin.CommandValidationException;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class UpgradeServerCommandTest extends TestCase {

    @Mock
    private HttpURLConnection httpURLConnection;

    @InjectMocks
    @Spy
    private UpgradeServerCommand upgradeServerCommand = new UpgradeServerCommand();

    @Test
    public void testPreventVersionDowngrade() throws CommandValidationException {
        List<String> selectedVersionList = new ArrayList<>();
        selectedVersionList.add("5.33.0");

        doReturn(selectedVersionList).when(upgradeServerCommand).getVersion();
        doReturn("5").when(upgradeServerCommand).getCurrentMajorVersion();
        doReturn("34").when(upgradeServerCommand).getCurrentMinorVersion();
        doReturn("0").when(upgradeServerCommand).getCurrentUpdatedVersion();

        try {
            upgradeServerCommand.preventVersionDowngrade();
        } catch (CommandValidationException e) {
            assertTrue(e.getMessage().contains("The version indicated is incorrect"));
            verify(upgradeServerCommand, times(1)).getVersion();
            verify(upgradeServerCommand, times(1)).getCurrentMajorVersion();
            verify(upgradeServerCommand, times(1)).getCurrentMinorVersion();
            verify(upgradeServerCommand, times(1)).getCurrentMinorVersion();
            verify(upgradeServerCommand, times(1)).throwCommandValidationException(anyString(), anyString());
        }
    }

    @Test
    public void testBadFormatForMajorVersion() {
        List<String> selectedVersionList = new ArrayList<>();
        selectedVersionList.add("a.24.0");

        doReturn(selectedVersionList).when(upgradeServerCommand).getVersion();

        try {
            upgradeServerCommand.preventVersionDowngrade();
        } catch (CommandValidationException e) {
            assertTrue(e.getMessage().contains("Invalid selected version"));
            verify(upgradeServerCommand, times(1)).getVersion();
        }

    }

    @Test
    public void testBadFormatForMinorVersion() {
        List<String> selectedVersionList = new ArrayList<>();
        selectedVersionList.add("5.b.0");

        doReturn(selectedVersionList).when(upgradeServerCommand).getVersion();

        try {
            upgradeServerCommand.preventVersionDowngrade();
        } catch (CommandValidationException e) {
            assertTrue(e.getMessage().contains("Invalid selected version"));
            verify(upgradeServerCommand, times(1)).getVersion();
        }
    }

    @Test
    public void testBadFormatForUpdatedVersion() {
        List<String> selectedVersionList = new ArrayList<>();
        selectedVersionList.add("5.24.0-SNAPSHOT");

        doReturn(selectedVersionList).when(upgradeServerCommand).getVersion();

        try {
            upgradeServerCommand.preventVersionDowngrade();
        } catch (CommandValidationException e) {
            assertTrue(e.getMessage().contains("Invalid selected version"));
            verify(upgradeServerCommand, times(1)).getVersion();
        }
    }

    @Test
    public void emptySelectedVersion() {
        List<String> selectedVersionList = new ArrayList<>();

        doReturn(selectedVersionList).when(upgradeServerCommand).getVersion();

        try {
            upgradeServerCommand.preventVersionDowngrade();
        } catch (CommandValidationException e) {
            assertTrue(e.getMessage().contains("Empty selected version"));
            verify(upgradeServerCommand, times(1)).getVersion();
        }
    }

    @Test
    public void testPreventSameVersionUpgrade() {
        List<String> selectedVersionList = new ArrayList<>();
        selectedVersionList.add("5.34.0");

        doReturn(selectedVersionList).when(upgradeServerCommand).getVersion();
        doReturn("5").when(upgradeServerCommand).getCurrentMajorVersion();
        doReturn("34").when(upgradeServerCommand).getCurrentMinorVersion();
        doReturn("0").when(upgradeServerCommand).getCurrentUpdatedVersion();

        try {
            upgradeServerCommand.preventVersionDowngrade();
        } catch (CommandValidationException e) {
            assertTrue(e.getMessage().contains("It was selected the same version"));
            verify(upgradeServerCommand, times(1)).getVersion();
            verify(upgradeServerCommand, times(1)).getCurrentMajorVersion();
            verify(upgradeServerCommand, times(1)).getCurrentMinorVersion();
            verify(upgradeServerCommand, times(1)).getCurrentMinorVersion();
        }
    }

    @Test
    public void testVersionUpgrade() {
        List<String> selectedVersionList = new ArrayList<>();
        selectedVersionList.add("6.1.0");

        doReturn(selectedVersionList).when(upgradeServerCommand).getVersion();
        doReturn("5").when(upgradeServerCommand).getCurrentMajorVersion();
        doReturn("34").when(upgradeServerCommand).getCurrentMinorVersion();
        doReturn("0").when(upgradeServerCommand).getCurrentUpdatedVersion();

        try {
            upgradeServerCommand.preventVersionDowngrade();
        } catch (CommandValidationException e) {
            Assert.fail("Exception thrown");
        }
        verify(upgradeServerCommand, times(1)).getVersion();
        verify(upgradeServerCommand, times(1)).getCurrentMajorVersion();
        verify(upgradeServerCommand, times(1)).getCurrentMinorVersion();
        verify(upgradeServerCommand, times(1)).getCurrentMinorVersion();

    }

    @Test
    public void testValidation404Error() throws IOException {
        doReturn(httpURLConnection).when(upgradeServerCommand).getConnection(anyString());
        when(httpURLConnection.getResponseCode()).thenReturn(404);

        int result = upgradeServerCommand.executeCommand();

        assertEquals(CLICommand.ERROR, result);
    }

}