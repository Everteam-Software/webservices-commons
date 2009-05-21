/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *   * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.axis2.transport.mail;

import java.io.IOException;
import java.io.OutputStream;

import org.apache.axiom.om.util.UUIDGenerator;
import org.apache.commons.io.output.CountingOutputStream;

import javax.mail.internet.MimeMessage;
import javax.mail.MessagingException;
import javax.mail.Session;

/**
 * The default MimeMessage does not let us set a custom MessageID on a message being
 * sent. This class allows us to overcome this limitation, but SMTP servers such as
 * GMail, re-writes this to an ID they define. Thats why the custom header defined by
 * MailConstants.MAIL_HEADER_X_MESSAGE_ID has been introduced, so that a client can
 * find out the relationship of a response to his request
 */
public class WSMimeMessage extends MimeMessage {
    private long bytesSent = -1;

    WSMimeMessage(Session session) {
        super(session);
    }

    @Override
    protected void updateMessageID() throws MessagingException {
	    if (getHeader(MailConstants.MAIL_HEADER_MESSAGE_ID) == null) {
            setHeader(MailConstants.MAIL_HEADER_MESSAGE_ID, UUIDGenerator.getUUID());    
        }
    }

    @Override
    public void writeTo(OutputStream out, String[] ignoreHeaders)
            throws MessagingException, IOException {
        if (bytesSent == -1) {
            CountingOutputStream countingOut = new CountingOutputStream(out);
            super.writeTo(countingOut, ignoreHeaders);
            bytesSent = countingOut.getByteCount();
        } else {
            super.writeTo(out, ignoreHeaders);
        }
    }

    public long getBytesSent() {
        return bytesSent;
    }
}
