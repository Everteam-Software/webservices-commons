/*
* Copyright 2004,2005 The Apache Software Foundation.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.axis2.transport.jms;

import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.transport.base.BaseUtils;
import org.apache.axis2.transport.base.BaseConstants;
import org.apache.axis2.transport.base.MetricsCollector;
import org.apache.axis2.transport.jms.ctype.ContentTypeInfo;
import org.apache.axis2.description.Parameter;
import org.apache.axis2.description.AxisService;
import org.apache.axis2.description.AxisOperation;
import org.apache.axis2.context.MessageContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import javax.jms.*;
import javax.xml.namespace.QName;
import javax.transaction.UserTransaction;

/**
 * This is the JMS message receiver which is invoked when a message is received. This processes
 * the message through the engine
 */
public class JMSMessageReceiver {

    private static final Log log = LogFactory.getLog(JMSMessageReceiver.class);

    /** The JMSListener */
    private JMSListener jmsListener = null;
    /** A reference to the JMS Connection Factory */
    private JMSConnectionFactory jmsConnectionFactory = null;
    /** The JMS metrics collector */
    private MetricsCollector metrics = null;
    /** The endpoint this message receiver is bound to */
    final JMSEndpoint endpoint;

    /**
     * Create a new JMSMessage receiver
     *
     * @param jmsListener the JMS transport Listener
     * @param jmsConFac   the JMS connection factory we are associated with
     * @param workerPool  the worker thread pool to be used
     * @param cfgCtx      the axis ConfigurationContext
     * @param serviceName the name of the Axis service
     * @param endpoint    the JMSEndpoint definition to be used
     */
    JMSMessageReceiver(JMSListener jmsListener, JMSConnectionFactory jmsConFac, JMSEndpoint endpoint) {
        this.jmsListener = jmsListener;
        this.jmsConnectionFactory = jmsConFac;
        this.endpoint = endpoint;
        this.metrics = jmsListener.getMetricsCollector();
    }

    /**
     * Process a new message received
     *
     * @param message the JMS message received
     * @param ut      UserTransaction which was used to receive the message
     * @return true if caller should commit
     */
    public boolean onMessage(Message message, UserTransaction ut) {

        try {
            if (log.isDebugEnabled()) {
                StringBuffer sb = new StringBuffer();
                sb.append("Received new JMS message for service :").append(endpoint.getServiceName());
                sb.append("\nDestination    : ").append(message.getJMSDestination());
                sb.append("\nMessage ID     : ").append(message.getJMSMessageID());
                sb.append("\nCorrelation ID : ").append(message.getJMSCorrelationID());
                sb.append("\nReplyTo        : ").append(message.getJMSReplyTo());
                sb.append("\nRedelivery ?   : ").append(message.getJMSRedelivered());
                sb.append("\nPriority       : ").append(message.getJMSPriority());
                sb.append("\nExpiration     : ").append(message.getJMSExpiration());
                sb.append("\nTimestamp      : ").append(message.getJMSTimestamp());
                sb.append("\nMessage Type   : ").append(message.getJMSType());
                sb.append("\nPersistent ?   : ").append(
                    DeliveryMode.PERSISTENT == message.getJMSDeliveryMode());

                log.debug(sb.toString());
                if (log.isTraceEnabled() && message instanceof TextMessage) {
                    log.trace("\nMessage : " + ((TextMessage) message).getText());
                }
            }
        } catch (JMSException e) {
            if (log.isDebugEnabled()) {
                log.debug("Error reading JMS message headers for debug logging", e);
            }
        }

        // update transport level metrics
        try {
            metrics.incrementBytesReceived(JMSUtils.getMessageSize(message));
        } catch (JMSException e) {
            log.warn("Error reading JMS message size to update transport metrics", e);
        }

        // has this message already expired? expiration time == 0 means never expires
        try {
            long expiryTime = message.getJMSExpiration();
            if (expiryTime > 0 && System.currentTimeMillis() > expiryTime) {
                if (log.isDebugEnabled()) {
                    log.debug("Discard expired message with ID : " + message.getJMSMessageID());
                }
                return true;
            }
        } catch (JMSException ignore) {}


        boolean successful = false;
        Throwable cause = null;
        Holder<MessageContext> holder = new Holder<MessageContext>();
        try {
            successful = processThoughEngine(message, ut, holder);

        } catch (JMSException e) {
            log.error("JMS Exception encountered while processing", cause = e);
        } catch (AxisFault e) {
            log.error("Axis fault processing message", cause = e);
        } catch (Exception e) {
            log.error("Unknown error processing message", cause = e);
        } finally {
            if (successful) {
                metrics.incrementMessagesReceived();
            } else {
                metrics.incrementFaultsReceiving();
                JMSUtils.handleDeadLetter(holder.getDelegate(), message, jmsConnectionFactory, cause);
            }
        }

        return successful;
    }

    /**
     * Process the new message through Axis2
     *
     * @param message the JMS message
     * @param ut      the UserTransaction used for receipt
     * @return true if the caller should commit
     * @throws JMSException, on JMS exceptions
     * @throws AxisFault     on Axis2 errors
     */
    private boolean processThoughEngine(Message message, UserTransaction ut, Holder<MessageContext> holder)
        throws JMSException, AxisFault {

        MessageContext msgContext = jmsListener.createMessageContext();
        holder.setDelegate(msgContext);

        // set the JMS Message ID as the Message ID of the MessageContext
        try {
            msgContext.setMessageID(message.getJMSMessageID());
            msgContext.setProperty(JMSConstants.JMS_COORELATION_ID, message.getJMSCorrelationID());
        } catch (JMSException ignore) {}

        String soapAction = JMSUtils.getProperty(message, BaseConstants.SOAPACTION);

        AxisService service = endpoint.getService();
        msgContext.setAxisService(service);

        // find the operation for the message, or default to one
        Parameter operationParam = service.getParameter(BaseConstants.OPERATION_PARAM);
        QName operationQName = (
            operationParam != null ?
                BaseUtils.getQNameFromString(operationParam.getValue()) :
                BaseConstants.DEFAULT_OPERATION);

        AxisOperation operation = service.getOperation(operationQName);
        if (operation != null) {
            msgContext.setAxisOperation(operation);
            msgContext.setSoapAction("urn:" + operation.getName().getLocalPart());
        }

        ContentTypeInfo contentTypeInfo =
            endpoint.getContentTypeRuleSet().getContentTypeInfo(message);
        if (contentTypeInfo == null) {
            throw new AxisFault("Unable to determine content type for message " +
                msgContext.getMessageID());
        }

        // set the message property OUT_TRANSPORT_INFO
        // the reply is assumed to be over the JMSReplyTo destination, using
        // the same incoming connection factory, if a JMSReplyTo is available
        Destination replyTo = message.getJMSReplyTo();
        if (replyTo == null) {
            // does the service specify a default reply destination ?
            Parameter param = service.getParameter(JMSConstants.PARAM_REPLY_DESTINATION);
            if (param != null && param.getValue() != null) {
                replyTo = jmsConnectionFactory.getDestination((String) param.getValue());
            }

        }
        if (replyTo != null) {
            msgContext.setProperty(Constants.OUT_TRANSPORT_INFO,
                new JMSOutTransportInfo(jmsConnectionFactory, replyTo,
                    contentTypeInfo.getPropertyName()));
        }

        JMSUtils.setSOAPEnvelope(message, msgContext, contentTypeInfo.getContentType());
        if (ut != null) {
            msgContext.setProperty(BaseConstants.USER_TRANSACTION, ut);
        }

        try {
            jmsListener.handleIncomingMessage(
                msgContext,
                JMSUtils.getTransportHeaders(message),
                soapAction,
                contentTypeInfo.getContentType());

        } finally {

            Object o = msgContext.getProperty(BaseConstants.SET_ROLLBACK_ONLY);
            if (o != null) {
                if ((o instanceof Boolean && ((Boolean) o)) ||
                    (o instanceof String && Boolean.valueOf((String) o))) {
                    return false;
                }
            }
            return true;
        }
    }
    
    public class Holder<T> {
    	T delegate;

		public T getDelegate() {
			return delegate;
		}

		public void setDelegate(T delegate) {
			this.delegate = delegate;
		}
    }
}
