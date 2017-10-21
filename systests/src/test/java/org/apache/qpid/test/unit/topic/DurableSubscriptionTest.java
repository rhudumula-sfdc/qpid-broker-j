/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.test.unit.topic;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.jms.Connection;
import javax.jms.ExceptionListener;
import javax.jms.InvalidDestinationException;
import javax.jms.InvalidSelectorException;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;
import javax.jms.TopicConnection;
import javax.jms.TopicSubscriber;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.client.AMQConnection;
import org.apache.qpid.client.AMQNoRouteException;
import org.apache.qpid.client.AMQQueue;
import org.apache.qpid.client.AMQSession;
import org.apache.qpid.test.utils.QpidBrokerTestCase;

/**
 * @todo Code to check that a consumer gets only one particular method could be factored into a re-usable method (as
 *       a static on a base test helper class, e.g. TestUtils.
 *
 * @todo Code to create test end-points using session per connection, or all sessions on one connection, to be factored
 *       out to make creating this test variation simpler. Want to make this variation available through LocalCircuit,
 *       driven by the test model.
 */
public class DurableSubscriptionTest extends QpidBrokerTestCase
{
    private static final Logger _logger = LoggerFactory.getLogger(DurableSubscriptionTest.class);

    private static final String MY_TOPIC = "MyTopic";

    private static final String MY_SUBSCRIPTION = "MySubscription";

    public void testUnsubscribe() throws Exception
    {
        TopicConnection con = (TopicConnection) getConnection();
        Topic topic = createTopic(con, "MyDurableSubscriptionTestTopic");
        _logger.info("Create Session 1");
        Session session1 = con.createSession(false, AMQSession.NO_ACKNOWLEDGE);
        _logger.info("Create Consumer on Session 1");
        MessageConsumer consumer1 = session1.createConsumer(topic);
        _logger.info("Create Producer on Session 1");
        MessageProducer producer = session1.createProducer(topic);

        _logger.info("Create Session 2");
        Session session2 = con.createSession(false, AMQSession.NO_ACKNOWLEDGE);
        _logger.info("Create Durable Subscriber on Session 2");
        TopicSubscriber consumer2 = session2.createDurableSubscriber(topic, MY_SUBSCRIPTION);

        _logger.info("Starting connection");
        con.start();

        _logger.info("Producer sending message A");
        producer.send(session1.createTextMessage("A"));

        //check the dur sub's underlying queue now has msg count 1
        AMQQueue subQueue = new AMQQueue("amq.topic", "clientid" + ":" + MY_SUBSCRIPTION);
        assertEquals("Msg count should be 1", 1, ((AMQSession<?, ?>) session1).getQueueDepth(subQueue, true));

        Message msg;
        _logger.info("Receive message on consumer 1:expecting A");
        msg = consumer1.receive(getReceiveTimeout());
        assertNotNull("Message should have been received",msg);
        assertEquals("A", ((TextMessage) msg).getText());
        _logger.info("Receive message on consumer 1 :expecting null");
        msg = consumer1.receive(getShortReceiveTimeout());
        assertEquals(null, msg);

        _logger.info("Receive message on consumer 2:expecting A");
        msg = consumer2.receive(getReceiveTimeout());
        assertNotNull("Message should have been received",msg);
        assertEquals("A", ((TextMessage) msg).getText());
        msg = consumer2.receive(getShortReceiveTimeout());
        _logger.info("Receive message on consumer 1 :expecting null");
        assertEquals(null, msg);

        //check the dur sub's underlying queue now has msg count 0
        assertEquals("Msg count should be 0", 0, ((AMQSession<?, ?>) session2).getQueueDepth(subQueue, true));

        consumer2.close();
        _logger.info("Unsubscribe session2/consumer2");
        session2.unsubscribe(MY_SUBSCRIPTION);
        
        ((AMQSession<?, ?>) session2).sync();
        
        if(isJavaBroker())
        {
            assertFalse("Queue " + subQueue + " exists", ((AMQSession<?, ?>) session2).isQueueBound(subQueue));
        }
        
        //verify unsubscribing the durable subscriber did not affect the non-durable one
        _logger.info("Producer sending message B");
        producer.send(session1.createTextMessage("B"));

        _logger.info("Receive message on consumer 1 :expecting B");
        msg = consumer1.receive(getReceiveTimeout());
        assertNotNull("Message should have been received",msg);
        assertEquals("B", ((TextMessage) msg).getText());
        _logger.info("Receive message on consumer 1 :expecting null");
        msg = consumer1.receive(getShortReceiveTimeout());
        assertEquals(null, msg);

        _logger.info("Close connection");
        con.close();
    }


    /**
     * Specifically uses a subscriber with a selector because QPID-4731 found that selectors
     * can prevent queue removal.
     */
    public void testUnsubscribeWhenUsingSelectorMakesTopicUnreachable() throws Exception
    {
        setTestClientSystemProperty("qpid.default_mandatory_topic","true");

        // set up subscription
        AMQConnection connection = (AMQConnection) getConnection();
        connection.start();

        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic topic = createTopic(connection, MY_TOPIC);
        MessageProducer producer = session.createProducer(topic);

        TopicSubscriber subscriber = session.createDurableSubscriber(topic, MY_SUBSCRIPTION, "1 = 1", false);
        StoringExceptionListener exceptionListener = new StoringExceptionListener();
        connection.setExceptionListener(exceptionListener);

        // send message and verify it was consumed
        producer.send(session.createTextMessage("message1"));
        assertNotNull("Message should have been successfully received", subscriber.receive(getReceiveTimeout()));
        assertEquals(null, exceptionListener.getException());
        session.unsubscribe(MY_SUBSCRIPTION);

        // send another message and verify that the connection exception listener was fired.
        StoringExceptionListener exceptionListener2 = new StoringExceptionListener();
        connection.setExceptionListener(exceptionListener2);

        producer.send(session.createTextMessage("message that should be unroutable"));
        ((AMQSession<?, ?>) session).sync();

        JMSException exception = exceptionListener2.awaitException();
        assertNotNull("Expected exception as message should no longer be routable", exception);

        Throwable linkedException = exception.getLinkedException();
        assertNotNull("The linked exception of " + exception + " should be the 'no route' exception", linkedException);
        assertEquals(AMQNoRouteException.class, linkedException.getClass());
    }

    private final class StoringExceptionListener implements ExceptionListener
    {
        private volatile JMSException _exception;
        private CountDownLatch _latch = new CountDownLatch(1);

        @Override
        public void onException(JMSException exception)
        {
            _exception = exception;
            _logger.info("Exception listener received: " + exception);
            _latch.countDown();
        }

        public JMSException awaitException() throws InterruptedException
        {
            _latch.await(getReceiveTimeout(), TimeUnit.MILLISECONDS);
            return _exception;
        }

        public JMSException getException()
        {
            return _exception;
        }
    }

    public void testDurabilityNOACK() throws Exception
    {
        durabilityImpl(AMQSession.NO_ACKNOWLEDGE, false);
    }

    public void testDurabilityAUTOACK() throws Exception
    {
        durabilityImpl(Session.AUTO_ACKNOWLEDGE, false);
    }
    
    public void testDurabilityAUTOACKwithRestartIfPersistent() throws Exception
    {
        if(!isBrokerStorePersistent())
        {
            _logger.warn("The broker store is not persistent, skipping this test");
            return;
        }
        
        durabilityImpl(Session.AUTO_ACKNOWLEDGE, true);
    }

    public void testDurabilityNOACKSessionPerConnection() throws Exception
    {
        durabilityImplSessionPerConnection(AMQSession.NO_ACKNOWLEDGE);
    }

    public void testDurabilityAUTOACKSessionPerConnection() throws Exception
    {
        durabilityImplSessionPerConnection(Session.AUTO_ACKNOWLEDGE);
    }

    private void durabilityImpl(int ackMode, boolean restartBroker) throws Exception
    {        
        TopicConnection con = (TopicConnection) getConnection();
        Topic topic = createTopic(con, MY_TOPIC);
        Session session1 = con.createSession(false, ackMode);
        MessageConsumer consumer1 = session1.createConsumer(topic);

        Session sessionProd = con.createSession(false, ackMode);
        MessageProducer producer = sessionProd.createProducer(topic);

        Session session2 = con.createSession(false, ackMode);
        TopicSubscriber consumer2 = session2.createDurableSubscriber(topic, MY_SUBSCRIPTION);

        con.start();

        //send message A and check both consumers receive
        producer.send(session1.createTextMessage("A"));

        Message msg;
        _logger.info("Receive message on consumer 1 :expecting A");
        msg = consumer1.receive(getReceiveTimeout());
        assertNotNull("Message should have been received",msg);
        assertEquals("A", ((TextMessage) msg).getText());
        msg = consumer1.receive(getShortReceiveTimeout());
        assertEquals(null, msg);

        _logger.info("Receive message on consumer 2 :expecting A");
        msg = consumer2.receive(getReceiveTimeout());
        assertNotNull("Message should have been received",msg);
        assertEquals("A", ((TextMessage) msg).getText());
        msg = consumer2.receive(getShortReceiveTimeout());
        assertEquals(null, msg);

        //send message B, receive with consumer 1, and disconnect consumer 2 to leave the message behind (if not NO_ACK)
        producer.send(session1.createTextMessage("B"));

        _logger.info("Receive message on consumer 1 :expecting B");
        msg = consumer1.receive(getReceiveTimeout());
        assertNotNull("Consumer 1 should get message 'B'.", msg);
        assertEquals("Incorrect Message received on consumer1.", "B", ((TextMessage) msg).getText());
        _logger.info("Receive message on consumer 1 :expecting null");
        msg = consumer1.receive(getShortReceiveTimeout());
        assertNull("There should be no more messages for consumption on consumer1.", msg);

        consumer2.close();
        session2.close();

        //Send message C, then connect consumer 3 to durable subscription and get
        //message B if not using NO_ACK, then receive C with consumer 1 and 3
        producer.send(session1.createTextMessage("C"));

        Session session3 = con.createSession(false, ackMode);
        MessageConsumer consumer3 = session3.createDurableSubscriber(topic, MY_SUBSCRIPTION);

        if(ackMode == AMQSession.NO_ACKNOWLEDGE)
        {
            //Do nothing if NO_ACK was used, as prefetch means the message was dropped
            //when we didn't call receive() to get it before closing consumer 2
        }
        else
        {
            _logger.info("Receive message on consumer 3 :expecting B");
            msg = consumer3.receive(getReceiveTimeout());
            assertNotNull("Consumer 3 should get message 'B'.", msg);
            assertEquals("Incorrect Message received on consumer3.", "B", ((TextMessage) msg).getText());
        }

        _logger.info("Receive message on consumer 1 :expecting C");
        msg = consumer1.receive(getReceiveTimeout());
        assertNotNull("Consumer 1 should get message 'C'.", msg);
        assertEquals("Incorrect Message received on consumer1.", "C", ((TextMessage) msg).getText());
        _logger.info("Receive message on consumer 1 :expecting null");
        msg = consumer1.receive(getShortReceiveTimeout());
        assertNull("There should be no more messages for consumption on consumer1.", msg);

        _logger.info("Receive message on consumer 3 :expecting C");
        msg = consumer3.receive(getReceiveTimeout());
        assertNotNull("Consumer 3 should get message 'C'.", msg);
        assertEquals("Incorrect Message received on consumer3.", "C", ((TextMessage) msg).getText());
        _logger.info("Receive message on consumer 3 :expecting null");
        msg = consumer3.receive(getShortReceiveTimeout());
        assertNull("There should be no more messages for consumption on consumer3.", msg);

        consumer1.close();
        consumer3.close();

        session3.unsubscribe(MY_SUBSCRIPTION);

        con.close();
        
        if(restartBroker)
        {
            try
            {
                restartDefaultBroker();
            }
            catch (Exception e)
            {
                fail("Error restarting the broker");
            }
        }
    }

    private void durabilityImplSessionPerConnection(int ackMode) throws Exception
    {
        Message msg;
        // Create producer.
        TopicConnection con0 = (TopicConnection) getConnection();
        con0.start();
        Session session0 = con0.createSession(false, ackMode);

        Topic topic = createTopic(con0, MY_TOPIC);

        Session sessionProd = con0.createSession(false, ackMode);
        MessageProducer producer = sessionProd.createProducer(topic);

        // Create consumer 1.
        Connection con1 = getConnection();
        con1.start();
        Session session1 = con1.createSession(false, ackMode);

        MessageConsumer consumer1 = session1.createConsumer(topic);

        // Create consumer 2.
        Connection con2 = getConnection();
        con2.start();
        Session session2 = con2.createSession(false, ackMode);

        TopicSubscriber consumer2 = session2.createDurableSubscriber(topic, MY_SUBSCRIPTION);

        // Send message and check that both consumers get it and only it.
        producer.send(session0.createTextMessage("A"));

        msg = consumer1.receive(getReceiveTimeout());
        assertNotNull("Message should be available", msg);
        assertEquals("Message Text doesn't match", "A", ((TextMessage) msg).getText());
        msg = consumer1.receive(getShortReceiveTimeout());
        assertNull("There should be no more messages for consumption on consumer1.", msg);

        msg = consumer2.receive(getReceiveTimeout());
        assertNotNull("Message should have been received",msg);
        assertEquals("Consumer 2 should also received the first msg.", "A", ((TextMessage) msg).getText());
        msg = consumer2.receive(getShortReceiveTimeout());
        assertNull("There should be no more messages for consumption on consumer2.", msg);

        // Send message and receive on consumer 1.
        producer.send(session0.createTextMessage("B"));

        _logger.info("Receive message on consumer 1 :expecting B");
        msg = consumer1.receive(getReceiveTimeout());
        assertEquals("B", ((TextMessage) msg).getText());
        _logger.info("Receive message on consumer 1 :expecting null");
        msg = consumer1.receive(getShortReceiveTimeout());
        assertEquals(null, msg);
        
        // Detach the durable subscriber.
        consumer2.close();
        session2.close();
        con2.close();
        
        // Send message C and receive on consumer 1
        producer.send(session0.createTextMessage("C"));

        _logger.info("Receive message on consumer 1 :expecting C");
        msg = consumer1.receive(getReceiveTimeout());
        assertEquals("C", ((TextMessage) msg).getText());
        _logger.info("Receive message on consumer 1 :expecting null");
        msg = consumer1.receive(getShortReceiveTimeout());
        assertEquals(null, msg);

        // Re-attach a new consumer to the durable subscription, and check that it gets message B it left (if not NO_ACK)
        // and also gets message C sent after it was disconnected.
        AMQConnection con3 = (AMQConnection) getConnection();
        con3.start();
        Session session3 = con3.createSession(false, ackMode);

        TopicSubscriber consumer3 = session3.createDurableSubscriber(topic, MY_SUBSCRIPTION);

        if(ackMode == AMQSession.NO_ACKNOWLEDGE)
        {
            //Do nothing if NO_ACK was used, as prefetch means the message was dropped
            //when we didn't call receive() to get it before closing consumer 2
        }
        else
        {
            _logger.info("Receive message on consumer 3 :expecting B");
            msg = consumer3.receive(getReceiveTimeout());
            assertNotNull(msg);
            assertEquals("B", ((TextMessage) msg).getText());
        }
        
        _logger.info("Receive message on consumer 3 :expecting C");
        msg = consumer3.receive(getReceiveTimeout());
        assertNotNull("Consumer 3 should get message 'C'.", msg);
        assertEquals("Incorrect Message recevied on consumer3.", "C", ((TextMessage) msg).getText());
        _logger.info("Receive message on consumer 3 :expecting null");
        msg = consumer3.receive(getShortReceiveTimeout());
        assertNull("There should be no more messages for consumption on consumer3.", msg);

        consumer1.close();
        consumer3.close();

        session3.unsubscribe(MY_SUBSCRIPTION);

        con0.close();
        con1.close();
        con3.close();
    }

    /**
     * This tests the fix for QPID-1085
     * Creates a durable subscriber with an invalid selector, checks that the
     * exception is thrown correctly and that the subscription is not created. 
     * @throws Exception 
     */
    public void testDurableWithInvalidSelector() throws Exception
    {
    	Connection conn = getConnection();
    	conn.start();
    	Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
    	Topic topic = createTopic(conn, "MyTestDurableWithInvalidSelectorTopic");
    	MessageProducer producer = session.createProducer(topic);
    	producer.send(session.createTextMessage("testDurableWithInvalidSelector1"));
    	try 
    	{
    		TopicSubscriber deadSubscriber = session.createDurableSubscriber(topic, "testDurableWithInvalidSelectorSub",
																	 		 "=TEST 'test", true);
    		assertNull("Subscriber should not have been created", deadSubscriber);
    	} 
    	catch (JMSException e)
    	{
    		assertTrue("Wrong type of exception thrown", e instanceof InvalidSelectorException);
    	}
    	TopicSubscriber liveSubscriber = session.createDurableSubscriber(topic, "testDurableWithInvalidSelectorSub");
    	assertNotNull("Subscriber should have been created", liveSubscriber);

    	producer.send(session.createTextMessage("testDurableWithInvalidSelector2"));
    	
    	Message msg = liveSubscriber.receive(getReceiveTimeout());
    	assertNotNull ("Message should have been received", msg);
    	assertEquals ("testDurableWithInvalidSelector2", ((TextMessage) msg).getText());
    	assertNull("Should not receive subsequent message", liveSubscriber.receive(getShortReceiveTimeout()));
        liveSubscriber.close();
        session.unsubscribe("testDurableWithInvalidSelectorSub");
    }
    
    /**
     * This tests the fix for QPID-1085
     * Creates a durable subscriber with an invalid destination, checks that the
     * exception is thrown correctly and that the subscription is not created. 
     * @throws Exception 
     */
    public void testDurableWithInvalidDestination() throws Exception
    {
    	Connection conn = getConnection();
    	conn.start();
    	Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
    	Topic topic = createTopic(conn, "testDurableWithInvalidDestinationTopic");
    	try 
    	{
    		TopicSubscriber deadSubscriber = session.createDurableSubscriber(null, "testDurableWithInvalidDestinationsub");
    		assertNull("Subscriber should not have been created", deadSubscriber);
    	} 
    	catch (InvalidDestinationException e)
    	{
    		// This was expected
    	}
    	MessageProducer producer = session.createProducer(topic);    	
    	producer.send(session.createTextMessage("testDurableWithInvalidSelector1"));
    	
    	TopicSubscriber liveSubscriber = session.createDurableSubscriber(topic, "testDurableWithInvalidDestinationsub");
    	assertNotNull("Subscriber should have been created", liveSubscriber);
    	
    	producer.send(session.createTextMessage("testDurableWithInvalidSelector2"));
    	Message msg = liveSubscriber.receive(getReceiveTimeout());
    	assertNotNull ("Message should have been received", msg);
    	assertEquals ("testDurableWithInvalidSelector2", ((TextMessage) msg).getText());
    	assertNull("Should not receive subsequent message", liveSubscriber.receive(getShortReceiveTimeout()));

        session.unsubscribe("testDurableWithInvalidDestinationsub");
    }
    
    /**
     * Creates a durable subscription with a selector, then changes that selector on resubscription
     * <p>
     * QPID-1202, QPID-2418
     */
    public void testResubscribeWithChangedSelector() throws Exception
    {
        Connection conn = getConnection();
        conn.start();
        Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic topic = createTopic(conn, "testResubscribeWithChangedSelector");
        MessageProducer producer = session.createProducer(topic);
        
        // Create durable subscriber that matches A
        TopicSubscriber subA = session.createDurableSubscriber(topic, 
                "testResubscribeWithChangedSelector",
                "Match = True", false);

        // Send 1 matching message and 1 non-matching message
        sendMatchingAndNonMatchingMessage(session, producer);

        Message rMsg = subA.receive(getShortReceiveTimeout());
        assertNotNull(rMsg);
        assertEquals("Content was wrong", 
                     "testResubscribeWithChangedSelector1",
                     ((TextMessage) rMsg).getText());
        
        rMsg = subA.receive(getShortReceiveTimeout());
        assertNull(rMsg);
        
        // Disconnect subscriber
        subA.close();
        
        // Reconnect with new selector that matches B
        TopicSubscriber subB = session.createDurableSubscriber(topic, 
                "testResubscribeWithChangedSelector","Match = False", false);

        //verify no messages are now received.
        rMsg = subB.receive(getShortReceiveTimeout());
        assertNull("Should not have received message as the selector was changed", rMsg);

        // Check that new messages are received properly
        sendMatchingAndNonMatchingMessage(session, producer);
        rMsg = subB.receive(getReceiveTimeout());

        assertNotNull("Message should have been received", rMsg);
        assertEquals("Content was wrong", 
                     "testResubscribeWithChangedSelector2",
                     ((TextMessage) rMsg).getText());
        
        
        rMsg = subB.receive(getShortReceiveTimeout());
        assertNull("Message should not have been received",rMsg);
        session.unsubscribe("testResubscribeWithChangedSelector");
    }

    public void testDurableSubscribeWithTemporaryTopic() throws Exception
    {
        Connection conn = getConnection();
        conn.start();
        Session ssn = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic topic = ssn.createTemporaryTopic();
        try
        {
            ssn.createDurableSubscriber(topic, "test");
            fail("expected InvalidDestinationException");
        }
        catch (InvalidDestinationException ex)
        {
            // this is expected
        }
        try
        {
            ssn.createDurableSubscriber(topic, "test", null, false);
            fail("expected InvalidDestinationException");
        }
        catch (InvalidDestinationException ex)
        {
            // this is expected
        }
    }

    private void sendMatchingAndNonMatchingMessage(Session session, MessageProducer producer) throws JMSException
    {
        TextMessage msg = session.createTextMessage("testResubscribeWithChangedSelector1");
        msg.setBooleanProperty("Match", true);
        producer.send(msg);
        msg = session.createTextMessage("testResubscribeWithChangedSelector2");
        msg.setBooleanProperty("Match", false);
        producer.send(msg);
    }


    /**
     * create and register a durable subscriber with a message selector and then close it
     * create a publisher and send  5 right messages and 5 wrong messages
     * create another durable subscriber with the same selector and name
     * check messages are still there
     * <p>
     * QPID-2418
     */
    public void testDurSubSameMessageSelector() throws Exception
    {        
        Connection conn = getConnection();
        conn.start();
        Session session = conn.createSession(true, Session.SESSION_TRANSACTED);
        Topic topic = createTopic(conn, "sameMessageSelector");
                
        //create and register a durable subscriber with a message selector and then close it
        TopicSubscriber subOne = session.createDurableSubscriber(topic, "sameMessageSelector", "testprop = TRUE", false);
        subOne.close();

        MessageProducer producer = session.createProducer(topic);
        for (int i = 0; i < 5; i++)
        {
            Message message = session.createMessage();
            message.setBooleanProperty("testprop", true);
            producer.send(message);
            message = session.createMessage();
            message.setBooleanProperty("testprop", false);
            producer.send(message);
        }
        session.commit();
        producer.close();

        // should be 5 or 10 messages on queue now
        // (5 for the Apache Qpid Broker-J due to use of server side selectors, and 10 for the cpp broker due to client side selectors only)
        AMQQueue queue = new AMQQueue("amq.topic", "clientid" + ":" + "sameMessageSelector");
        assertEquals("Queue depth is wrong", isJavaBroker() ? 5 : 10, ((AMQSession<?, ?>) session).getQueueDepth(queue, true));

        // now recreate the durable subscriber and check the received messages
        TopicSubscriber subTwo = session.createDurableSubscriber(topic, "sameMessageSelector", "testprop = TRUE", false);

        for (int i = 0; i < 5; i++)
        {
            Message message = subTwo.receive(getReceiveTimeout());
            if (message == null)
            {
                fail("sameMessageSelector test failed. no message was returned");
            }
            else
            {
                assertEquals("sameMessageSelector test failed. message selector not reset",
                        "true", message.getStringProperty("testprop"));
            }
        }
        
        session.commit();
        
        // Check queue has no messages
        if (isJavaBroker())
        {
            assertEquals("Queue should be empty", 0, ((AMQSession<?, ?>) session).getQueueDepth(queue));
        }
        else
        {
            assertTrue("At most the queue should have only 1 message", ((AMQSession<?, ?>) session).getQueueDepth(queue) <= 1);
        }
        
        // Unsubscribe
        session.unsubscribe("sameMessageSelector");
        
        conn.close();
    }

    /**
     * <ul>
     * <li>create and register a durable subscriber with a message selector
     * <li>create another durable subscriber with a different selector and same name
     * <li>check first subscriber is now closed
     * <li>create a publisher and send messages
     * <li>check messages are received correctly
     * </ul>
     * <p>
     * QPID-2418
     */
    public void testResubscribeWithChangedSelectorNoClose() throws Exception
    {
        Connection conn = getConnection();
        conn.start();
        Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic topic = createTopic(conn, "testResubscribeWithChangedSelectorNoClose");
        
        // Create durable subscriber that matches A
        TopicSubscriber subA = session.createDurableSubscriber(topic, 
                "testResubscribeWithChangedSelectorNoClose",
                "Match = True", false);
        
        // Reconnect with new selector that matches B
        TopicSubscriber subB = session.createDurableSubscriber(topic, 
                "testResubscribeWithChangedSelectorNoClose",
                "Match = false", false);
        
        // First subscription has been closed
        try
        {
            subA.receive(getShortReceiveTimeout());
            fail("First subscription was not closed");
        }
        catch (Exception e)
        {
            _logger.error("Receive error",e);
        }

        conn.stop();
        
        // Send 1 matching message and 1 non-matching message
        MessageProducer producer = session.createProducer(topic);
        TextMessage msg = session.createTextMessage("testResubscribeWithChangedSelectorAndRestart1");
        msg.setBooleanProperty("Match", true);
        producer.send(msg);
        msg = session.createTextMessage("testResubscribeWithChangedSelectorAndRestart2");
        msg.setBooleanProperty("Match", false);
        producer.send(msg);

        // should be 1 or 2 messages on queue now
        // (1 for the Apache Qpid Broker-J due to use of server side selectors, and 2 for the cpp broker due to client side selectors only)
        AMQQueue queue = new AMQQueue("amq.topic", "clientid" + ":" + "testResubscribeWithChangedSelectorNoClose");
        assertEquals("Queue depth is wrong", isJavaBroker() ? 1 : 2, ((AMQSession<?, ?>) session).getQueueDepth(queue, true));

        conn.start();
        
        Message rMsg = subB.receive(getReceiveTimeout());
        assertNotNull(rMsg);
        assertEquals("Content was wrong", 
                     "testResubscribeWithChangedSelectorAndRestart2",
                     ((TextMessage) rMsg).getText());
        
        rMsg = subB.receive(getShortReceiveTimeout());
        assertNull(rMsg);
        
        // Check queue has no messages
        assertEquals("Queue should be empty", 0, ((AMQSession<?, ?>) session).getQueueDepth(queue, true));
        
        conn.close();
    }

    /**
     * <ul>
     * <li>create and register a durable subscriber with no message selector
     * <li>create another durable subscriber with a selector and same name
     * <li>check first subscriber is now closed
     * <li>create a publisher and send  messages
     * <li>check messages are received correctly
     * </ul>
     * <p>
     * QPID-2418
     */
    public void testDurSubAddMessageSelectorNoClose() throws Exception
    {        
        Connection conn = getConnection();
        conn.start();
        Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic topic = createTopic(conn, "subscriptionName");
                
        // create and register a durable subscriber with no message selector
        TopicSubscriber subOne = session.createDurableSubscriber(topic, "subscriptionName", null, false);

        // now create a durable subscriber with a selector
        TopicSubscriber subTwo = session.createDurableSubscriber(topic, "subscriptionName", "testprop = TRUE", false);

        // First subscription has been closed
        try
        {
            subOne.receive(getShortReceiveTimeout());
            fail("First subscription was not closed");
        }
        catch (Exception e)
        {
            _logger.error("Receive error",e);
        }

        conn.stop();
        
        // Send 1 matching message and 1 non-matching message
        MessageProducer producer = session.createProducer(topic);
        TextMessage msg = session.createTextMessage("testResubscribeWithChangedSelectorAndRestart1");
        msg.setBooleanProperty("testprop", true);
        producer.send(msg);
        msg = session.createTextMessage("testResubscribeWithChangedSelectorAndRestart2");
        msg.setBooleanProperty("testprop", false);
        producer.send(msg);

        // should be 1 or 2 messages on queue now
        // (1 for the Apache Qpid Broker-J due to use of server side selectors, and 2 for the cpp broker due to client side selectors only)
        AMQQueue queue = new AMQQueue("amq.topic", "clientid" + ":" + "subscriptionName");
        assertEquals("Queue depth is wrong", isJavaBroker() ? 1 : 2, ((AMQSession<?, ?>) session).getQueueDepth(queue, true));
        
        conn.start();
        
        Message rMsg = subTwo.receive(getReceiveTimeout());
        assertNotNull(rMsg);
        assertEquals("Content was wrong", 
                     "testResubscribeWithChangedSelectorAndRestart1",
                     ((TextMessage) rMsg).getText());
        
        rMsg = subTwo.receive(getShortReceiveTimeout());
        assertNull(rMsg);
        
        // Check queue has no messages
        assertEquals("Queue should be empty", 0, ((AMQSession<?, ?>) session).getQueueDepth(queue, true));
        
        conn.close();
    }

    /**
     * <ul>
     * <li>create and register a durable subscriber with no message selector
     * <li>try to create another durable with the same name, should fail
     * </ul>
     * <p>
     * QPID-2418
     */
    public void testDurSubNoSelectorResubscribeNoClose() throws Exception
    {        
        Connection conn = getConnection();
        conn.start();
        Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic topic = createTopic(conn, "subscriptionName");
                
        // create and register a durable subscriber with no message selector
        session.createDurableSubscriber(topic, "subscriptionName", null, false);

        // try to recreate the durable subscriber
        try
        {
            session.createDurableSubscriber(topic, "subscriptionName", null, false);
            fail("Subscription should not have been created");
        }
        catch (Exception e)
        {
            _logger.error("Error creating durable subscriber",e);
        }
    }

    /**
     * Tests that a subscriber created on a same <i>session</i> as producer with
     * no local true does not receive messages.
     */
    public void testNoLocalOnSameSession() throws Exception
    {
        Connection connection = getConnection();
        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic topic = session.createTopic(getTestQueueName());
        MessageProducer producer = session.createProducer(topic);
        TopicSubscriber subscriber =  null;
        try
        {
            subscriber = session.createDurableSubscriber(topic, getTestName(), null, true);
            connection.start();

            producer.send(createNextMessage(session, 1));

            Message m = subscriber.receive(getShortReceiveTimeout());
            assertNull("Unexpected message received", m);
        }
        finally
        {
            session.unsubscribe(getTestName());
        }
    }


    /**
     * Tests that a subscriber created on a same <i>connection</i> but separate
     * <i>sessionM</i> as producer with no local true does not receive messages.
     */
    public void testNoLocalOnSameConnection() throws Exception
    {
        Connection connection = getConnection();

        Session consumerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Session producerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic topic = consumerSession.createTopic(getTestQueueName());
        MessageProducer producer = producerSession.createProducer(topic);

        TopicSubscriber subscriber =  null;
        try
        {
            subscriber = consumerSession.createDurableSubscriber(topic, getTestName(), null, true);
            connection.start();

            producer.send(createNextMessage(producerSession, 1));

            Message m = subscriber.receive(getShortReceiveTimeout());
            assertNull("Unexpected message received", m);
        }
        finally
        {
            consumerSession.unsubscribe(getTestName());
        }
    }

    /**
     * Tests that if no-local is in use, that the messages are delivered when
     * the client reconnects.
     *
     * Currently fails on the Apache Qpid Broker-J due to QPID-3605.
     */
    public void testNoLocalMessagesNotDeliveredAfterReconnection() throws Exception
    {
        Connection connection = getConnection();

        Session consumerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Session producerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic topic = consumerSession.createTopic(getTestQueueName());
        MessageProducer producer = producerSession.createProducer(topic);

        TopicSubscriber subscriber =  null;
        try
        {
            subscriber = consumerSession.createDurableSubscriber(topic, getTestName(), null, true);
            connection.start();

            producer.send(createNextMessage(producerSession, 1));

            Message m = subscriber.receive(getShortReceiveTimeout());
            assertNull("Unexpected message received", m);

            connection.close();

            connection = getConnection();

            consumerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            subscriber = consumerSession.createDurableSubscriber(topic, getTestName(), null, true);
            connection.start();
            m = subscriber.receive(getShortReceiveTimeout());
            assertNull("Message should not be received on a new connection", m);
        }
        finally
        {
            consumerSession.unsubscribe(getTestName());
        }
    }

    /**
     * Tests that messages are delivered normally to a subscriber on a separate connection despite
     * the use of durable subscriber with no-local on the first connection.
     */
    public void testNoLocalSubscriberAndSubscriberOnSeparateConnection() throws Exception
    {
        Connection noLocalConnection = getConnection();
        Connection connection = getConnection();

        String noLocalSubId1 = getTestName() + "subId1";
        String subId = getTestName() + "subId2";

        Session noLocalSession = noLocalConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic noLocalTopic = noLocalSession.createTopic(getTestQueueName());

        Session consumerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic topic = consumerSession.createTopic(getTestQueueName());

        TopicSubscriber noLocalSubscriber =  null;
        TopicSubscriber subscriber =  null;
        try
        {
            MessageProducer producer = noLocalSession.createProducer(noLocalTopic);
            noLocalSubscriber = noLocalSession.createDurableSubscriber(noLocalTopic, noLocalSubId1, null, true);
            subscriber = consumerSession.createDurableSubscriber(topic, subId, null, true);
            noLocalConnection.start();
            connection.start();

            producer.send(createNextMessage(noLocalSession, 1));

            Message m1 = noLocalSubscriber.receive(getShortReceiveTimeout());
            assertNull("Subscriber on nolocal connection should not receive message", m1);

            Message m2 = subscriber.receive(getShortReceiveTimeout());
            assertNotNull("Subscriber on non-nolocal connection should receive message", m2);
        }
        finally
        {
            noLocalSession.unsubscribe(noLocalSubId1);
            consumerSession.unsubscribe(subId);
        }
    }
}
