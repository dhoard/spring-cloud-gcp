/*
 *  Copyright 2018 original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.springframework.cloud.gcp.pubsub.support;

import com.google.protobuf.Empty;
import com.google.pubsub.v1.PubsubMessage;

import org.springframework.util.concurrent.ListenableFuture;

/**
 * A {@link PubsubMessage} wrapper that allows it to be acknowledged.
 *
 * @author João André Martins
 * @author Mike Eltsufin
 * @author Doug Hoard
 *
 * @since 1.1
 */
public interface BasicAcknowledgeablePubsubMessage {

	/**
	 * Accessor for the project id of the Pub/Sub message;
	 * @return the project id
	 */
	String getProjectId();

	/*
	 * Accessor for the subscription source of the Pub/Sub message.
	 * @return the name of the subscription
	 */
	String getSubscriptionName();

	/**
	 * Accessor for the wrapped {@link PubsubMessage}.
	 * @return the wrapped Pub/Sub message
	 */
	PubsubMessage getPubsubMessage();

	/**
	 * Acknowledge (ack) the message asynchronously
	 * @return ListenableFuture&lt;String&gt;
	 */
	ListenableFuture<Empty> ack();

	/**
	 * Negatatively achnowledge (nack) the message asynchronously
	 * @return ListenableFuture&lt;String&gt;
	 */
	ListenableFuture<Empty> nack();

}
