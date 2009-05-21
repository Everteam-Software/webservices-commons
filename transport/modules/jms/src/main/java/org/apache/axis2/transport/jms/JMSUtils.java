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

import org.apache.axiom.om.OMElement;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.builder.Builder;
import org.apache.axis2.builder.BuilderUtil;
import org.apache.axis2.builder.SOAPBuilder;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.description.AxisService;
import org.apache.axis2.description.Parameter;
import org.apache.axis2.format.DataSourceMessageBuilder;
import org.apache.axis2.format.TextMessageBuilder;
import org.apache.axis2.format.TextMessageBuilderAdapter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.axis2.transport.TransportUtils;
import org.apache.axis2.transport.base.BaseUtils;
import org.apache.axis2.transport.base.BaseConstants;
import org.apache.axis2.transport.base.threads.WorkerPool;

import javax.jms.*;
import javax.jms.Queue;
import javax.mail.internet.ContentType;
import javax.mail.internet.ParseException;
import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.Reference;
import javax.xml.stream.XMLStreamException;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Miscallaneous methods used for the JMS transport
 */
public class JMSUtils extends BaseUtils {

    private static final Log log = LogFactory.getLog(JMSUtils.class);
    private static final Class[]  NOARGS  = new Class[] {};
    private static final Object[] NOPARMS = new Object[] {};

    /**
     * Should this service be enabled over the JMS transport?
     *
     * @param service the Axis service
     * @return true if JMS should be enabled
     */
    public static boolean isJMSService(AxisService service) {
        if (service.isEnableAllTransports()) {
            return true;

        } else {
            List transports = service.getExposedTransports();
            for (Object transport : transports) {
                if (JMSListener.TRANSPORT_NAME.equals(transport)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get the EPR for the given JMS connection factory and destination
     * the form of the URL is
     * jms:/<destination>?[<key>=<value>&]*
     * Credentials Context.SECURITY_PRINCIPAL, Context.SECURITY_CREDENTIALS
     * JMSConstants.PARAM_JMS_USERNAME and JMSConstants.PARAM_JMS_USERNAME are filtered
     *
     * @param cf the Axis2 JMS connection factory
     * @param destinationType the type of destination
     * @param endpoint JMSEndpoint
     * @return the EPR as a String
     */
    static String getEPR(JMSConnectionFactory cf, int destinationType, JMSEndpoint endpoint) {
        StringBuffer sb = new StringBuffer();

        sb.append(
            JMSConstants.JMS_PREFIX).append(endpoint.getJndiDestinationName());
        sb.append("?").
            append(JMSConstants.PARAM_DEST_TYPE).append("=").append(
            destinationType == JMSConstants.TOPIC ?
                JMSConstants.DESTINATION_TYPE_TOPIC : JMSConstants.DESTINATION_TYPE_QUEUE);

        if (endpoint.getContentTypeRuleSet() != null) {
            String contentTypeProperty =
                endpoint.getContentTypeRuleSet().getDefaultContentTypeProperty();
            if (contentTypeProperty != null) {
                sb.append("&");
                sb.append(JMSConstants.CONTENT_TYPE_PROPERTY_PARAM);
                sb.append("=");
                sb.append(contentTypeProperty);
            }
        }

        for (Map.Entry<String,String> entry : cf.getParameters().entrySet()) {
            if (!Context.SECURITY_PRINCIPAL.equalsIgnoreCase(entry.getKey()) &&
                !Context.SECURITY_CREDENTIALS.equalsIgnoreCase(entry.getKey()) &&
                !JMSConstants.PARAM_JMS_USERNAME.equalsIgnoreCase(entry.getKey()) &&
                !JMSConstants.PARAM_JMS_PASSWORD.equalsIgnoreCase(entry.getKey())) {
                sb.append("&").append(
                    entry.getKey()).append("=").append(entry.getValue());
            }
        }
        return sb.toString();
    }

    /**
     * Get a String property from the JMS message
     *
     * @param message  JMS message
     * @param property property name
     * @return property value
     */
    public static String getProperty(Message message, String property) {
        try {
            return message.getStringProperty(property);
        } catch (JMSException e) {
            return null;
        }
    }

    /**
     * Return the destination name from the given URL
     *
     * @param url the URL
     * @return the destination name
     */
    public static String getDestination(String url) {
        String tempUrl = url.substring(JMSConstants.JMS_PREFIX.length());
        int propPos = tempUrl.indexOf("?");

        if (propPos == -1) {
            return tempUrl;
        } else {
            return tempUrl.substring(0, propPos);
        }
    }

    /**
     * Set the SOAPEnvelope to the Axis2 MessageContext, from the JMS Message passed in
     * @param message the JMS message read
     * @param msgContext the Axis2 MessageContext to be populated
     * @param contentType content type for the message
     * @throws AxisFault
     * @throws JMSException
     */
    public static void setSOAPEnvelope(Message message, MessageContext msgContext, String contentType)
        throws AxisFault, JMSException {

        if (contentType == null) {
            if (message instanceof TextMessage) {
                contentType = "text/plain";
            } else {
                contentType = "application/octet-stream";
            }
            if (log.isDebugEnabled()) {
                log.debug("No content type specified; assuming " + contentType);
            }
        }
        
        int index = contentType.indexOf(';');
        String type = index > 0 ? contentType.substring(0, index) : contentType;
        Builder builder = BuilderUtil.getBuilderFromSelector(type, msgContext);
        if (builder == null) {
            if (log.isDebugEnabled()) {
                log.debug("No message builder found for type '" + type + "'. Falling back to SOAP.");
            }
            builder = new SOAPBuilder();
        }
        
        OMElement documentElement;
        if (message instanceof BytesMessage) {
            // Extract the charset encoding from the content type and
            // set the CHARACTER_SET_ENCODING property as e.g. SOAPBuilder relies on this.
            String charSetEnc = null;
            try {
                if (contentType != null) {
                    charSetEnc = new ContentType(contentType).getParameter("charset");
                }
            } catch (ParseException ex) {
                // ignore
            }
            msgContext.setProperty(Constants.Configuration.CHARACTER_SET_ENCODING, charSetEnc);
            
            if (builder instanceof DataSourceMessageBuilder) {
                documentElement = ((DataSourceMessageBuilder)builder).processDocument(
                        new BytesMessageDataSource((BytesMessage)message), contentType,
                        msgContext);
            } else {
                documentElement = builder.processDocument(
                        new BytesMessageInputStream((BytesMessage)message), contentType,
                        msgContext);
            }
        } else if (message instanceof TextMessage) {
            TextMessageBuilder textMessageBuilder;
            if (builder instanceof TextMessageBuilder) {
                textMessageBuilder = (TextMessageBuilder)builder;
            } else {
                textMessageBuilder = new TextMessageBuilderAdapter(builder);
            }
            String content = ((TextMessage)message).getText();
            documentElement = textMessageBuilder.processDocument(content, contentType, msgContext);
        } else {
            handleException("Unsupported JMS message type " + message.getClass().getName());
            return; // Make compiler happy
        }
        msgContext.setEnvelope(TransportUtils.createSOAPEnvelope(documentElement));
    }

    /**
     * Set the JMS ReplyTo for the message
     *
     * @param replyDestination the JMS Destination where the reply is expected
     * @param session the session to use to create a temp Queue if a response is expected
     * but a Destination has not been specified
     * @param message the JMS message where the final Destinatio would be set as the JMS ReplyTo
     * @return the JMS ReplyTo Destination for the message
     */
    public static Destination setReplyDestination(Destination replyDestination, Session session,
        Message message) {

        if (replyDestination == null) {
           try {
               // create temporary queue to receive the reply
               replyDestination = createTemporaryDestination(session);
           } catch (JMSException e) {
               handleException("Error creating temporary queue for response");
           }
        }

        try {
            message.setJMSReplyTo(replyDestination);
        } catch (JMSException e) {
            log.warn("Error setting JMS ReplyTo destination to : " + replyDestination, e);
        }

        if (log.isDebugEnabled()) {
            try {
                assert replyDestination != null;
                log.debug("Expecting a response to JMS Destination : " +
                    (replyDestination instanceof Queue ?
                        ((Queue) replyDestination).getQueueName() :
                        ((Topic) replyDestination).getTopicName()));
            } catch (JMSException ignore) {}
        }
        return replyDestination;
    }

    /**
     * Set transport headers from the axis message context, into the JMS message
     *
     * @param msgContext the axis message context
     * @param message the JMS Message
     * @throws JMSException on exception
     */
    public static void setTransportHeaders(MessageContext msgContext, Message message)
        throws JMSException {

        Map headerMap = (Map) msgContext.getProperty(MessageContext.TRANSPORT_HEADERS);

        if (headerMap == null) {
            return;
        }

        for (Object headerName : headerMap.keySet()) {

            String name = (String) headerName;

            if (name.startsWith(JMSConstants.JMSX_PREFIX) &&
                !(name.equals(JMSConstants.JMSX_GROUP_ID) || name.equals(JMSConstants.JMSX_GROUP_SEQ))) {
                continue;
            }

            if (JMSConstants.JMS_COORELATION_ID.equals(name)) {
                message.setJMSCorrelationID(
                        (String) headerMap.get(JMSConstants.JMS_COORELATION_ID));
            } else if (JMSConstants.JMS_DELIVERY_MODE.equals(name)) {
                Object o = headerMap.get(JMSConstants.JMS_DELIVERY_MODE);
                if (o instanceof Integer) {
                    message.setJMSDeliveryMode((Integer) o);
                } else if (o instanceof String) {
                    try {
                        message.setJMSDeliveryMode(Integer.parseInt((String) o));
                    } catch (NumberFormatException nfe) {
                        log.warn("Invalid delivery mode ignored : " + o, nfe);
                    }
                } else {
                    log.warn("Invalid delivery mode ignored : " + o);
                }

            } else if (JMSConstants.JMS_EXPIRATION.equals(name)) {
                message.setJMSExpiration(
                    Long.parseLong((String) headerMap.get(JMSConstants.JMS_EXPIRATION)));
            } else if (JMSConstants.JMS_MESSAGE_ID.equals(name)) {
                message.setJMSMessageID((String) headerMap.get(JMSConstants.JMS_MESSAGE_ID));
            } else if (JMSConstants.JMS_PRIORITY.equals(name)) {
                message.setJMSPriority(
                    Integer.parseInt((String) headerMap.get(JMSConstants.JMS_PRIORITY)));
            } else if (JMSConstants.JMS_TIMESTAMP.equals(name)) {
                message.setJMSTimestamp(
                    Long.parseLong((String) headerMap.get(JMSConstants.JMS_TIMESTAMP)));
            } else if (JMSConstants.JMS_MESSAGE_TYPE.equals(name)) {
                message.setJMSType((String) headerMap.get(JMSConstants.JMS_MESSAGE_TYPE));

            } else {
                Object value = headerMap.get(name);
                if (value instanceof String) {
                    message.setStringProperty(name, (String) value);
                } else if (value instanceof Boolean) {
                    message.setBooleanProperty(name, (Boolean) value);
                } else if (value instanceof Integer) {
                    message.setIntProperty(name, (Integer) value);
                } else if (value instanceof Long) {
                    message.setLongProperty(name, (Long) value);
                } else if (value instanceof Double) {
                    message.setDoubleProperty(name, (Double) value);
                } else if (value instanceof Float) {
                    message.setFloatProperty(name, (Float) value);
                }
            }
        }
    }

    /**
     * Read the transport headers from the JMS Message and set them to the axis2 message context
     *
     * @param message the JMS Message received
     * @param responseMsgCtx the axis message context
     * @throws AxisFault on error
     */
    public static void loadTransportHeaders(Message message, MessageContext responseMsgCtx)
        throws AxisFault {
        responseMsgCtx.setProperty(MessageContext.TRANSPORT_HEADERS, getTransportHeaders(message));
    }

    /**
     * Extract transport level headers for JMS from the given message into a Map
     *
     * @param message the JMS message
     * @return a Map of the transport headers
     */
    public static Map<String, Object> getTransportHeaders(Message message) {
        // create a Map to hold transport headers
        Map<String, Object> map = new HashMap<String, Object>();

        // correlation ID
        try {
            if (message.getJMSCorrelationID() != null) {
                map.put(JMSConstants.JMS_COORELATION_ID, message.getJMSCorrelationID());
            }
        } catch (JMSException ignore) {}

        // set the delivery mode as persistent or not
        try {
            map.put(JMSConstants.JMS_DELIVERY_MODE, Integer.toString(message.getJMSDeliveryMode()));
        } catch (JMSException ignore) {}

        // destination name
        try {
            if (message.getJMSDestination() != null) {
                Destination dest = message.getJMSDestination();
                map.put(JMSConstants.JMS_DESTINATION,
                    dest instanceof Queue ?
                        ((Queue) dest).getQueueName() : ((Topic) dest).getTopicName());
            }
        } catch (JMSException ignore) {}

        // expiration
        try {
            map.put(JMSConstants.JMS_EXPIRATION, Long.toString(message.getJMSExpiration()));
        } catch (JMSException ignore) {}

        // if a JMS message ID is found
        try {
            if (message.getJMSMessageID() != null) {
                map.put(JMSConstants.JMS_MESSAGE_ID, message.getJMSMessageID());
            }
        } catch (JMSException ignore) {}

        // priority
        try {
            map.put(JMSConstants.JMS_PRIORITY, Long.toString(message.getJMSPriority()));
        } catch (JMSException ignore) {}

        // redelivered
        try {
            map.put(JMSConstants.JMS_REDELIVERED, Boolean.toString(message.getJMSRedelivered()));
        } catch (JMSException ignore) {}

        // replyto destination name
        try {
            if (message.getJMSReplyTo() != null) {
                Destination dest = message.getJMSReplyTo();
                map.put(JMSConstants.JMS_REPLY_TO,
                    dest instanceof Queue ?
                        ((Queue) dest).getQueueName() : ((Topic) dest).getTopicName());
            }
        } catch (JMSException ignore) {}

        // priority
        try {
            map.put(JMSConstants.JMS_TIMESTAMP, Long.toString(message.getJMSTimestamp()));
        } catch (JMSException ignore) {}

        // message type
        try {
            if (message.getJMSType() != null) {
                map.put(JMSConstants.JMS_TYPE, message.getJMSType());
            }
        } catch (JMSException ignore) {}

        // any other transport properties / headers
        Enumeration e = null;
        try {
            e = message.getPropertyNames();
        } catch (JMSException ignore) {}

        if (e != null) {
            while (e.hasMoreElements()) {
                String headerName = (String) e.nextElement();
                try {
                    map.put(headerName, message.getStringProperty(headerName));
                    continue;
                } catch (JMSException ignore) {}
                try {
                    map.put(headerName, message.getBooleanProperty(headerName));
                    continue;
                } catch (JMSException ignore) {}
                try {
                    map.put(headerName, message.getIntProperty(headerName));
                    continue;
                } catch (JMSException ignore) {}
                try {
                    map.put(headerName, message.getLongProperty(headerName));
                    continue;
                } catch (JMSException ignore) {}
                try {
                    map.put(headerName, message.getDoubleProperty(headerName));
                    continue;
                } catch (JMSException ignore) {}
                try {
                    map.put(headerName, message.getFloatProperty(headerName));
                } catch (JMSException ignore) {}
            }
        }

        return map;
    }


    /**
     * Create a MessageConsumer for the given Destination
     * @param session JMS Session to use
     * @param dest Destination for which the Consumer is to be created
     * @param messageSelector the message selector to be used if any
     * @return a MessageConsumer for the specified Destination
     * @throws JMSException
     */
    public static MessageConsumer createConsumer(Session session, Destination dest, String messageSelector)
        throws JMSException {

        if (dest instanceof Queue) {
            return ((QueueSession) session).createReceiver((Queue) dest, messageSelector);
        } else {
            return ((TopicSession) session).createSubscriber((Topic) dest, messageSelector, false);
        }
    }

    /**
     * Create a temp queue or topic for synchronous receipt of responses, when a reply destination
     * is not specified
     * @param session the JMS Session to use
     * @return a temporary Queue or Topic, depending on the session
     * @throws JMSException
     */
    public static Destination createTemporaryDestination(Session session) throws JMSException {

        if (session instanceof QueueSession) {
            return session.createTemporaryQueue();
        } else {
            return session.createTemporaryTopic();
        }
    }

    /**
     * Return the body length in bytes for a bytes message
     * @param bMsg the JMS BytesMessage
     * @return length of body in bytes
     */
    public static long getBodyLength(BytesMessage bMsg) {
        try {
            Method mtd = bMsg.getClass().getMethod("getBodyLength", NOARGS);
            if (mtd != null) {
                return (Long) mtd.invoke(bMsg, NOPARMS);
            }
        } catch (Exception e) {
            // JMS 1.0
            if (log.isDebugEnabled()) {
                log.debug("Error trying to determine JMS BytesMessage body length", e);
            }
        }

        // if JMS 1.0
        long length = 0;
        try {
            byte[] buffer = new byte[2048];
            bMsg.reset();
            for (int bytesRead = bMsg.readBytes(buffer); bytesRead != -1;
                 bytesRead = bMsg.readBytes(buffer)) {
                    length += bytesRead;
            }
        } catch (JMSException ignore) {}
        return length;
    }

    /**
     * Get the length of the message in bytes
     * @param message
     * @return message size (or approximation) in bytes
     * @throws JMSException
     */
    public static long getMessageSize(Message message) throws JMSException {
        if (message instanceof BytesMessage) {
            return JMSUtils.getBodyLength((BytesMessage) message);
        } else if (message instanceof TextMessage) {
            // TODO: Converting the whole message to a byte array is too much overhead just to determine the message size.
            //       Anyway, the result is not accurate since we don't know what encoding the JMS provider uses.
            return ((TextMessage) message).getText().getBytes().length;
        } else {
            log.warn("Can't determine size of JMS message; unsupported message type : "
                    + message.getClass().getName());
            return 0;
        }
    }
    
    public static <T> T lookup(Context context, Class<T> clazz, String name)
        throws NamingException {
        
        Object object = context.lookup(name);
        try {
            return clazz.cast(object);
        } catch (ClassCastException ex) {
            // Instead of a ClassCastException, throw an exception with some
            // more information.
            if (object instanceof Reference) {
                Reference ref = (Reference)object;
                handleException("JNDI failed to de-reference Reference with name " +
                        name + "; is the factory " + ref.getFactoryClassName() +
                        " in your classpath?");
                return null;
            } else {
                handleException("JNDI lookup of name " + name + " returned a " +
                        object.getClass().getName() + " while a " + clazz + " was expected");
                return null;
            }
        }
    }

    /**
     * Create a ServiceTaskManager for the service passed in and its corresponding JMSConnectionFactory
     * @param jcf
     * @param service
     * @param workerPool
     * @return
     */
    public static ServiceTaskManager createTaskManagerForService(JMSConnectionFactory jcf,
        AxisService service, WorkerPool workerPool) {

        String name = service.getName();
        Map<String, String> svc = getServiceStringParameters(service.getParameters());
        Map<String, String> cf  = jcf.getParameters();

        ServiceTaskManager stm = new ServiceTaskManager();

        stm.setServiceName(name);
        stm.addJmsProperties(cf);
        stm.addJmsProperties(svc);

        stm.setConnFactoryJNDIName(
            getRqdStringProperty(JMSConstants.PARAM_CONFAC_JNDI_NAME, svc, cf));
        String destName = getOptionalStringProperty(JMSConstants.PARAM_DESTINATION, svc, cf);
        if (destName == null) {
            destName = service.getName();
        }
        stm.setDestinationJNDIName(destName);
        stm.setDestinationType(getDestinationType(svc, cf));

        stm.setJmsSpec11(
            getJMSSpecVersion(svc, cf));
        stm.setTransactionality(
            getTransactionality(svc, cf));
        stm.setCacheUserTransaction(
            getOptionalBooleanProperty(BaseConstants.PARAM_CACHE_USER_TXN, svc, cf));
        stm.setUserTransactionJNDIName(
            getOptionalStringProperty(BaseConstants.PARAM_USER_TXN_JNDI_NAME, svc, cf));
        stm.setSessionTransacted(
            getOptionalBooleanProperty(JMSConstants.PARAM_SESSION_TRANSACTED, svc, cf));
        stm.setSessionAckMode(
            getSessionAck(svc, cf));
        stm.setMessageSelector(
            getOptionalStringProperty(JMSConstants.PARAM_MSG_SELECTOR, svc, cf));
        stm.setSubscriptionDurable(
            getOptionalBooleanProperty(JMSConstants.PARAM_SUB_DURABLE, svc, cf));
        stm.setDurableSubscriberName(
            getOptionalStringProperty(JMSConstants.PARAM_DURABLE_SUB_NAME, svc, cf));

        stm.setCacheLevel(
            getCacheLevel(svc, cf));
        stm.setPubSubNoLocal(
            getOptionalBooleanProperty(JMSConstants.PARAM_PUBSUB_NO_LOCAL, svc, cf));

        Integer value = getOptionalIntProperty(JMSConstants.PARAM_RCV_TIMEOUT, svc, cf);
        if (value != null) {
            stm.setReceiveTimeout(value);
        }
        value = getOptionalIntProperty(JMSConstants.PARAM_CONCURRENT_CONSUMERS, svc, cf);
        if (value != null) {
            stm.setConcurrentConsumers(value);
        }
        value = getOptionalIntProperty(JMSConstants.PARAM_MAX_CONSUMERS, svc, cf);
        if (value != null) {
            stm.setMaxConcurrentConsumers(value);
        }
        value = getOptionalIntProperty(JMSConstants.PARAM_IDLE_TASK_LIMIT, svc, cf);
        if (value != null) {
            stm.setIdleTaskExecutionLimit(value);
        }
        value = getOptionalIntProperty(JMSConstants.PARAM_MAX_MSGS_PER_TASK, svc, cf);
        if (value != null) {
            stm.setMaxMessagesPerTask(value);
        }

        value = getOptionalIntProperty(JMSConstants.PARAM_RECON_INIT_DURATION, svc, cf);
        if (value != null) {
            stm.setInitialReconnectDuration(value);
        }
        value = getOptionalIntProperty(JMSConstants.PARAM_RECON_MAX_DURATION, svc, cf);
        if (value != null) {
            stm.setMaxReconnectDuration(value);
        }
        Double dValue = getOptionalDoubleProperty(JMSConstants.PARAM_RECON_FACTOR, svc, cf);
        if (dValue != null) {
            stm.setReconnectionProgressionFactor(dValue);
        }

        stm.setWorkerPool(workerPool);

        // remove processed properties from property bag
        stm.removeJmsProperties(JMSConstants.PARAM_CONFAC_JNDI_NAME);
        stm.removeJmsProperties(JMSConstants.PARAM_DESTINATION);
        stm.removeJmsProperties(JMSConstants.PARAM_JMS_SPEC_VER);
        stm.removeJmsProperties(BaseConstants.PARAM_TRANSACTIONALITY);
        stm.removeJmsProperties(BaseConstants.PARAM_CACHE_USER_TXN);
        stm.removeJmsProperties(BaseConstants.PARAM_USER_TXN_JNDI_NAME);
        stm.removeJmsProperties(JMSConstants.PARAM_SESSION_TRANSACTED);
        stm.removeJmsProperties(JMSConstants.PARAM_MSG_SELECTOR);
        stm.removeJmsProperties(JMSConstants.PARAM_SUB_DURABLE);
        stm.removeJmsProperties(JMSConstants.PARAM_DURABLE_SUB_NAME);
        stm.removeJmsProperties(JMSConstants.PARAM_CACHE_LEVEL);
        stm.removeJmsProperties(JMSConstants.PARAM_PUBSUB_NO_LOCAL);
        stm.removeJmsProperties(JMSConstants.PARAM_RCV_TIMEOUT);
        stm.removeJmsProperties(JMSConstants.PARAM_CONCURRENT_CONSUMERS);
        stm.removeJmsProperties(JMSConstants.PARAM_MAX_CONSUMERS);
        stm.removeJmsProperties(JMSConstants.PARAM_IDLE_TASK_LIMIT);
        stm.removeJmsProperties(JMSConstants.PARAM_MAX_MSGS_PER_TASK);
        stm.removeJmsProperties(JMSConstants.PARAM_RECON_INIT_DURATION);
        stm.removeJmsProperties(JMSConstants.PARAM_RECON_MAX_DURATION);
        stm.removeJmsProperties(JMSConstants.PARAM_RECON_FACTOR);

        return stm;
    }

    private static Map<String, String> getServiceStringParameters(List list) {

        Map<String, String> map = new HashMap<String, String>();
        for (Object o : list) {
            Parameter p = (Parameter) o;
            if (p.getValue() instanceof String) {
                map.put(p.getName(), (String) p.getValue());
            }
        }
        return map;
    }

    private static String getRqdStringProperty(String key, Map svcMap, Map cfMap) {
        String value = (String) svcMap.get(key);
        if (value == null) {
            value = (String) cfMap.get(key);
        }
        if (value == null) {
            throw new AxisJMSException("Service/connection factory property : " + key);
        }
        return value;
    }

    private static String getOptionalStringProperty(String key, Map svcMap, Map cfMap) {
        String value = (String) svcMap.get(key);
        if (value == null) {
            value = (String) cfMap.get(key);
        }
        return value;
    }

    private static Boolean getOptionalBooleanProperty(String key, Map svcMap, Map cfMap) {
        String value = (String) svcMap.get(key);
        if (value == null) {
            value = (String) cfMap.get(key);
        }
        if (value == null) {
            return null;
        } else {
            return Boolean.valueOf(value);
        }
    }

    private static Integer getOptionalIntProperty(String key, Map svcMap, Map cfMap) {
        String value = (String) svcMap.get(key);
        if (value == null) {
            value = (String) cfMap.get(key);
        }
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new AxisJMSException("Invalid value : " + value + " for " + key);
            }
        }
        return null;
    }

    private static Double getOptionalDoubleProperty(String key, Map svcMap, Map cfMap) {
        String value = (String) svcMap.get(key);
        if (value == null) {
            value = (String) cfMap.get(key);
        }
        if (value != null) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e) {
                throw new AxisJMSException("Invalid value : " + value + " for " + key);
            }
        }
        return null;
    }

    private static int getTransactionality(Map svcMap, Map cfMap) {

        String key = BaseConstants.PARAM_TRANSACTIONALITY;
        String val = (String) svcMap.get(key);
        if (val == null) {
            val = (String) cfMap.get(key);
        }

        if (val == null) {
            return BaseConstants.TRANSACTION_NONE;

        } else {    
            if (BaseConstants.STR_TRANSACTION_JTA.equalsIgnoreCase(val)) {
                return BaseConstants.TRANSACTION_JTA;
            } else if (BaseConstants.STR_TRANSACTION_LOCAL.equalsIgnoreCase(val)) {
                return BaseConstants.TRANSACTION_LOCAL;
            } else {
                throw new AxisJMSException("Invalid option : " + val + " for parameter : " +
                    BaseConstants.STR_TRANSACTION_JTA);
            }
        }
    }

    private static int getDestinationType(Map svcMap, Map cfMap) {

        String key = JMSConstants.PARAM_DEST_TYPE;
        String val = (String) svcMap.get(key);
        if (val == null) {
            val = (String) cfMap.get(key);
        }

        if (JMSConstants.DESTINATION_TYPE_TOPIC.equalsIgnoreCase(val)) {
            return JMSConstants.TOPIC;
        }
        return JMSConstants.QUEUE;
    }

    private static int getSessionAck(Map svcMap, Map cfMap) {

        String key = JMSConstants.PARAM_SESSION_ACK;
        String val = (String) svcMap.get(key);
        if (val == null) {
            val = (String) cfMap.get(key);
        }

        if (val == null || "AUTO_ACKNOWLEDGE".equalsIgnoreCase(val)) {
            return Session.AUTO_ACKNOWLEDGE;
        } else if ("CLIENT_ACKNOWLEDGE".equalsIgnoreCase(val)) {
            return Session.CLIENT_ACKNOWLEDGE;
        } else if ("DUPS_OK_ACKNOWLEDGE".equals(val)){
            return Session.DUPS_OK_ACKNOWLEDGE;
        } else if ("SESSION_TRANSACTED".equals(val)) {
            return 0; //Session.SESSION_TRANSACTED;
        } else {
            try {
                return Integer.parseInt(val);
            } catch (NumberFormatException ignore) {
                throw new AxisJMSException("Invalid session acknowledgement mode : " + val);
            }
        }
    }

    private static int getCacheLevel(Map svcMap, Map cfMap) {

        String key = JMSConstants.PARAM_CACHE_LEVEL;
        String val = (String) svcMap.get(key);
        if (val == null) {
            val = (String) cfMap.get(key);
        }

        if ("none".equalsIgnoreCase(val)) {
            return JMSConstants.CACHE_NONE;
        } else if ("connection".equalsIgnoreCase(val)) {
            return JMSConstants.CACHE_CONNECTION;
        } else if ("session".equals(val)){
            return JMSConstants.CACHE_SESSION;
        } else if ("consumer".equals(val)) {
            return JMSConstants.CACHE_CONSUMER;
        } else if (val != null) {
            throw new AxisJMSException("Invalid cache level : " + val);
        }
        return JMSConstants.CACHE_AUTO;
    }

    private static boolean getJMSSpecVersion(Map svcMap, Map cfMap) {

        String key = JMSConstants.PARAM_JMS_SPEC_VER;
        String val = (String) svcMap.get(key);
        if (val == null) {
            val = (String) cfMap.get(key);
        }

        if (val == null || "1.1".equals(val)) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * This is a JMS spec independent method to create a Connection. Please be cautious when
     * making any changes
     *
     * @param conFac the ConnectionFactory to use
     * @param user optional user name
     * @param pass optional password
     * @param jmsSpec11 should we use JMS 1.1 API ?
     * @param isQueue is this to deal with a Queue?
     * @return a JMS Connection as requested
     * @throws JMSException on errors, to be handled and logged by the caller
     */
    public static Connection createConnection(ConnectionFactory conFac,
        String user, String pass, boolean jmsSpec11, Boolean isQueue) throws JMSException {

        Connection connection = null;
        if (log.isDebugEnabled()) {
            log.debug("Creating a " + (isQueue == null ? "Generic" : isQueue ? "Queue" : "Topic") +
                "Connection using credentials : (" + user + "/" + pass + ")");
        }

        if (jmsSpec11 || isQueue == null) {
            if (user != null && pass != null) {
                connection = conFac.createConnection(user, pass);
            } else {
                connection = conFac.createConnection();
            }

        } else {
            QueueConnectionFactory qConFac = null;
            TopicConnectionFactory tConFac = null;
            if (isQueue) {
                tConFac = (TopicConnectionFactory) conFac;
            } else {
                qConFac = (QueueConnectionFactory) conFac;
            }

            if (user != null && pass != null) {
                if (qConFac != null) {
                    connection = qConFac.createQueueConnection(user, pass);
                } else if (tConFac != null) {
                    connection = tConFac.createTopicConnection(user, pass);
                }
            } else {
                if (qConFac != null) {
                    connection = qConFac.createQueueConnection();
                } else if (tConFac != null) {
                    connection = tConFac.createTopicConnection();
                }
            }
        }
        return connection;
    }

    /**
     * This is a JMS spec independent method to create a Session. Please be cautious when
     * making any changes
     *
     * @param connection the JMS Connection
     * @param transacted should the session be transacted?
     * @param ackMode the ACK mode for the session
     * @param jmsSpec11 should we use the JMS 1.1 API?
     * @param isQueue is this Session to deal with a Queue?
     * @return a Session created for the given information
     * @throws JMSException on errors, to be handled and logged by the caller
     */
    public static Session createSession(Connection connection, boolean transacted, int ackMode,
        boolean jmsSpec11, Boolean isQueue) throws JMSException {

        if (jmsSpec11 || isQueue == null) {
            return connection.createSession(transacted, ackMode);

        } else {
            if (isQueue) {
                return ((QueueConnection) connection).createQueueSession(transacted, ackMode);
            } else {
                return ((TopicConnection) connection).createTopicSession(transacted, ackMode);
            }
        }
    }

    /**
     * This is a JMS spec independent method to create a MessageConsumer. Please be cautious when
     * making any changes
     *
     * @param session JMS session
     * @param destination the Destination
     * @param isQueue is the Destination a queue?
     * @param subscriberName optional client name to use for a durable subscription to a topic
     * @param messageSelector optional message selector
     * @param pubSubNoLocal should we receive messages sent by us during pub-sub?
     * @param isDurable is this a durable topic subscription?
     * @param jmsSpec11 should we use JMS 1.1 API ?
     * @return a MessageConsumer to receive messages
     * @throws JMSException on errors, to be handled and logged by the caller
     */
    public static MessageConsumer createConsumer(
        Session session, Destination destination, Boolean isQueue,
        String subscriberName, String messageSelector, boolean pubSubNoLocal,
        boolean isDurable, boolean jmsSpec11) throws JMSException {

        if (jmsSpec11 || isQueue == null) {
            if (isDurable) {
                return session.createDurableSubscriber(
                    (Topic) destination, subscriberName, messageSelector, pubSubNoLocal);
            } else {
                return session.createConsumer(destination, messageSelector, pubSubNoLocal);
            }
        } else {
            if (isQueue) {
                return ((QueueSession) session).createReceiver((Queue) destination, messageSelector);
            } else {
                if (isDurable) {
                    return ((TopicSession) session).createDurableSubscriber(
                        (Topic) destination, subscriberName, messageSelector, pubSubNoLocal);
                } else {
                    return ((TopicSession) session).createSubscriber(
                        (Topic) destination, messageSelector, pubSubNoLocal);
                }
            }
        }
    }

    /**
     * This is a JMS spec independent method to create a MessageProducer. Please be cautious when
     * making any changes
     *
     * @param session JMS session
     * @param destination the Destination
     * @param isQueue is the Destination a queue?
     * @param jmsSpec11 should we use JMS 1.1 API ?
     * @return a MessageProducer to send messages to the given Destination
     * @throws JMSException on errors, to be handled and logged by the caller
     */
    public static MessageProducer createProducer(
        Session session, Destination destination, Boolean isQueue, boolean jmsSpec11) throws JMSException {

        if (jmsSpec11 || isQueue == null) {
            return session.createProducer(destination);
        } else {
            if (isQueue) {
                return ((QueueSession) session).createSender((Queue) destination);
            } else {
                return ((TopicSession) session).createPublisher((Topic) destination);               
            }
        }
    }

    /**
     * Create a one time MessageProducer for the given JMS OutTransport information
     * For simplicity and best compatibility, this method uses only JMS 1.0.2b API.
     * Please be cautious when making any changes
     *
     * @param jmsOut the JMS OutTransport information (contains all properties)
     * @return a JMSSender based on one-time use resources
     * @throws JMSException on errors, to be handled and logged by the caller 
     */
    public static JMSMessageSender createJMSSender(JMSOutTransportInfo jmsOut)
        throws JMSException {

        // digest the targetAddress and locate CF from the EPR
        jmsOut.loadConnectionFactoryFromProperies();

        // create a one time connection and session to be used
        Hashtable<String,String> jmsProps = jmsOut.getProperties();
        String user = jmsProps != null ? jmsProps.get(JMSConstants.PARAM_JMS_USERNAME) : null;
        String pass = jmsProps != null ? jmsProps.get(JMSConstants.PARAM_JMS_PASSWORD) : null;

        QueueConnectionFactory qConFac = null;
        TopicConnectionFactory tConFac = null;

        int destType = -1;
        if (JMSConstants.DESTINATION_TYPE_QUEUE.equals(jmsOut.getDestinationType())) {
            destType = JMSConstants.QUEUE;
            qConFac = (QueueConnectionFactory) jmsOut.getConnectionFactory();

        } else if (JMSConstants.DESTINATION_TYPE_TOPIC.equals(jmsOut.getDestinationType())) {
            destType = JMSConstants.TOPIC;
            tConFac = (TopicConnectionFactory) jmsOut.getConnectionFactory();
        }

        Connection connection = null;
        if (user != null && pass != null) {
            if (qConFac != null) {
                connection = qConFac.createQueueConnection(user, pass);
            } else if (tConFac != null) {
                connection = tConFac.createTopicConnection(user, pass);
            }
        } else {
           if (qConFac != null) {
                connection = qConFac.createQueueConnection();
            } else if (tConFac != null)  {
                connection = tConFac.createTopicConnection();
            }
        }

        if (connection == null && jmsOut.getJmsConnectionFactory() != null) {
            connection = jmsOut.getJmsConnectionFactory().getConnection();
        }

        Session session = null;
        MessageProducer producer = null;
        Destination destination = jmsOut.getDestination();

        if (destType == JMSConstants.QUEUE) {
            session = ((QueueConnection) connection).
                createQueueSession(false, Session.AUTO_ACKNOWLEDGE);
            producer = ((QueueSession) session).createSender((Queue) destination);
        } else {
            session = ((TopicConnection) connection).
                createTopicSession(false, Session.AUTO_ACKNOWLEDGE);
            producer = ((TopicSession) session).createPublisher((Topic) destination);
        }

        return new JMSMessageSender(connection, session, producer,
            destination, (jmsOut.getJmsConnectionFactory() == null ?
            JMSConstants.CACHE_NONE : jmsOut.getJmsConnectionFactory().getCacheLevel()), false,
            destType == -1 ? null : destType == JMSConstants.QUEUE ? Boolean.TRUE : Boolean.FALSE);
    }

    /**
     * Return a String representation of the destination type
     * @param destType the destination type indicator int
     * @return a descriptive String
     */
    public static String getDestinationTypeAsString(int destType) {
        if (destType == JMSConstants.QUEUE) {
            return "Queue";
        } else if (destType == JMSConstants.TOPIC) {
            return "Topic";
        } else {
            return "Generic";
        }
    }
}
