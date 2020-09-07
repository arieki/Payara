/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2016-2020 Payara Foundation and/or its affiliates. All rights reserved.
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
package fish.payara.internal.notification;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.MessageFormat;
import java.util.logging.Level;

import javax.inject.Inject;

import com.sun.enterprise.config.serverbeans.Server;

import org.glassfish.api.admin.ServerEnvironment;
import org.glassfish.hk2.api.ServiceLocator;
import org.jvnet.hk2.annotations.Service;

/**
 * Factory for building {@link NotificationEvent}
 * @author mertcaliskan
 * @since 4.1.2.171
 */
@Service
public class PayaraNotificationFactory {

    @Inject
    private ServerEnvironment environment;

    @Inject
    private ServiceLocator habitat;

    /**
     * Creates a {@link NotificationEvent}
     * @param subject Subject of the message
     * i.e. what the subject line is if the event is sent to the Javamail notifier
     * @param message The message text of the event
     * @return the resulting {@link NotificationEvent}
     */
    public PayaraNotification buildNotificationEvent(String subject, String message) {
        PayaraNotification event = createEvent();
        event.setSubject(subject);
        event.setMessage(message);

        return event;
    }
    
    /**
     * Creates a {@link NotificationEvent}
     * @param level Severity level of notification. This is unused in the base factory
     * @param subject Subject of the message
     * i.e. what the subject line is if the event is sent to the Javamail notifier
     * @param message The message text of the event
     * @param parameters An additional parameters to be formatted as part of the message
     * @return the resulting {@link NotificationEvent}
     */
    public PayaraNotification buildNotificationEvent(Level level, String subject, String message, Object[] parameters) {
        PayaraNotification event = buildNotificationEvent(subject, message);
        if (parameters != null && parameters.length > 0) {
            message = MessageFormat.format(message, parameters);
        }
        event.setEventType(level.getName());
        return event;
    }

    public PayaraNotificationBuilder newBuilder() {
        return new PayaraNotificationBuilder(createEvent());
    }

    protected PayaraNotification createEvent() {
        PayaraNotification event = new PayaraNotification();
        try {
            event.setHostName(InetAddress.getLocalHost().getHostName());
        } catch (UnknownHostException ex) {
            //No-op
        }
        event.setDomainName(environment.getDomainName());
        event.setInstanceName(environment.getInstanceName());
        Server server = habitat.getService(Server.class, environment.getInstanceName());
        event.setServerName(server.getName());

        return event;
    }
}