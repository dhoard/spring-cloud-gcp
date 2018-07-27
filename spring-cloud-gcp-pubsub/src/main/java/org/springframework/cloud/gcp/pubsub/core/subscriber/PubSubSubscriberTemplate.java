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

package org.springframework.cloud.gcp.pubsub.core.subscriber;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.cloud.pubsub.v1.stub.SubscriberStub;
import com.google.pubsub.v1.AcknowledgeRequest;
import com.google.pubsub.v1.ModifyAckDeadlineRequest;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.PullRequest;
import com.google.pubsub.v1.PullResponse;

import org.springframework.cloud.gcp.pubsub.support.PulledAcknowledgeablePubsubMessage;
import org.springframework.cloud.gcp.pubsub.support.SubscriberFactory;
import org.springframework.util.Assert;

/**
 * Default implementation of {@link PubSubSubscriberOperations}.
 *
 * <p>The main Google Cloud Pub/Sub integration component for consuming
 * messages from subscriptions asynchronously or by pulling.
 *
 * @author Vinicius Carvalho
 * @author João André Martins
 * @author Mike Eltsufin
 * @author Chengyuan Zhao
 * @author Doug Hoard
 *
 * @since 1.1
 */
public class PubSubSubscriberTemplate implements PubSubSubscriberOperations {

	private final SubscriberFactory subscriberFactory;

	private final SubscriberStub subscriberStub;

	/**
	 * Default {@link PubSubSubscriberTemplate} constructor
	 * @param subscriberFactory the {@link Subscriber} factory
	 * to subscribe to subscriptions or pull messages.
	 */
	public PubSubSubscriberTemplate(SubscriberFactory subscriberFactory) {
		Assert.notNull(subscriberFactory, "The subscriberFactory can't be null.");

		this.subscriberFactory = subscriberFactory;
		this.subscriberStub = this.subscriberFactory.createSubscriberStub();
	}

	@Override
	public Subscriber subscribe(String subscription, MessageReceiver messageReceiver) {
		Subscriber subscriber =
				this.subscriberFactory.createSubscriber(subscription, messageReceiver);
		subscriber.startAsync();
		return subscriber;
	}

	/**
	 * Pulls messages synchronously, on demand, using the pull request in argument.
	 * @param pullRequest pull request containing the subscription name
	 * @return the list of {@link PulledAcknowledgeablePubsubMessage} containing the ack ID, subscription
	 * and acknowledger
	 */
	private List<PulledAcknowledgeablePubsubMessage> pull(PullRequest pullRequest) {
		Assert.notNull(pullRequest, "The pull request cannot be null.");

		PullResponse pullResponse =	this.subscriberStub.pullCallable().call(pullRequest);
		List<PulledAcknowledgeablePubsubMessage> receivedMessages =
				pullResponse.getReceivedMessagesList().stream()
						.map(message -> new PulledAcknowledgeablePubsubMessageImpl(message.getMessage(),
									message.getAckId(),
									pullRequest.getSubscription(),
									this.subscriberStub))
						.collect(Collectors.toList());

		return receivedMessages;
	}

	@Override
	public List<PulledAcknowledgeablePubsubMessage> pull(String subscription, Integer maxMessages,
			Boolean returnImmediately) {
		return pull(this.subscriberFactory.createPullRequest(subscription, maxMessages,
				returnImmediately));
	}

	@Override
	public List<PubsubMessage> pullAndAck(String subscription, Integer maxMessages,
			Boolean returnImmediately) {
		PullRequest pullRequest = this.subscriberFactory.createPullRequest(
				subscription, maxMessages, returnImmediately);

		List<PulledAcknowledgeablePubsubMessage> ackableMessages = pull(pullRequest);

		ack(ackableMessages);

		return ackableMessages.stream().map(PulledAcknowledgeablePubsubMessage::getPubsubMessage)
				.collect(Collectors.toList());
	}

	@Override
	public PubsubMessage pullNext(String subscription) {
		List<PubsubMessage> receivedMessageList = pullAndAck(subscription, 1, true);

		return receivedMessageList.size() > 0 ?	receivedMessageList.get(0) : null;
	}

	public SubscriberFactory getSubscriberFactory() {
		return this.subscriberFactory;
	}

	@Override
	public void ack(Collection<PulledAcknowledgeablePubsubMessage> pulledAcknowledgeablePubsubMessages) {
		Assert.notEmpty(pulledAcknowledgeablePubsubMessages, "The pulledAcknowledgeablePubsubMessages cannot be null.");

		groupPulledAcknowledgeableMessages(pulledAcknowledgeablePubsubMessages).forEach(this::ack);
	}

	@Override
	public void nack(Collection<PulledAcknowledgeablePubsubMessage> pulledAcknowledgeablePubsubMessages) {
		Assert.notEmpty(pulledAcknowledgeablePubsubMessages, "The pulledAcknowledgeablePubsubMessages cannot be null.");

		groupPulledAcknowledgeableMessages(pulledAcknowledgeablePubsubMessages).forEach(this::nack);
	}

	@Override
	public void modifyAckDeadline(Collection<PulledAcknowledgeablePubsubMessage> pulledAcknowledgeablePubsubMessages,
			int ackDeadlineSeconds) {
		Assert.notEmpty(pulledAcknowledgeablePubsubMessages, "The pulledAcknowledgeablePubsubMessages cannot be null.");
		Assert.isTrue(ackDeadlineSeconds >= 0, "The ackDeadlineSeconds must not be negative.");

		groupPulledAcknowledgeableMessages(pulledAcknowledgeablePubsubMessages)
				.forEach((sub, ackIds) -> modifyAckDeadline(sub, ackIds, ackDeadlineSeconds));
	}

	/**
	 * Groups {@link PulledAcknowledgeablePubsubMessage} messages by subscription.
	 * @return a map from subscription to list of ack IDs.
	 */
	private Map<String, List<String>> groupPulledAcknowledgeableMessages(
			Collection<PulledAcknowledgeablePubsubMessage> pulledAcknowledgeablePubsubMessages) {
		return pulledAcknowledgeablePubsubMessages.stream()
				.collect(Collectors.groupingBy(PulledAcknowledgeablePubsubMessage::getSubscriptionName,
						Collectors.mapping(PulledAcknowledgeablePubsubMessage::getAckId, Collectors.toList())));
	}

	private void ack(String subscriptionName, Collection<String> ackIds) {
		AcknowledgeRequest acknowledgeRequest = AcknowledgeRequest.newBuilder()
				.addAllAckIds(ackIds)
				.setSubscription(subscriptionName)
				.build();

		this.subscriberStub.acknowledgeCallable().call(acknowledgeRequest);
	}

	private void nack(String subscriptionName, Collection<String> ackIds) {
		modifyAckDeadline(subscriptionName, ackIds, 0);
	}

	private void modifyAckDeadline(String subscriptionName, Collection<String> ackIds, int ackDeadlineSeconds) {
		ModifyAckDeadlineRequest modifyAckDeadlineRequest = ModifyAckDeadlineRequest.newBuilder()
				.setAckDeadlineSeconds(ackDeadlineSeconds)
				.addAllAckIds(ackIds)
				.setSubscription(subscriptionName)
				.build();

		this.subscriberStub.modifyAckDeadlineCallable().call(modifyAckDeadlineRequest);
	}

	private class PulledAcknowledgeablePubsubMessageImpl implements PulledAcknowledgeablePubsubMessage {

		private PubsubMessage message;

		private String ackId;

		private String subscriptionName;

		private SubscriberStub subscriberStub;

		PulledAcknowledgeablePubsubMessageImpl(
				PubsubMessage message, String ackId, String subscriptionName, SubscriberStub subscriberStub) {
			this.message = message;
			this.ackId = ackId;
			this.subscriptionName = subscriptionName;
			this.subscriberStub = subscriberStub;
		}

		@Override
		public PubsubMessage getPubsubMessage() {
			return this.message;
		}

		public String getAckId() {
			return this.ackId;
		}

		public String getSubscriptionName() {
			return this.subscriptionName;
		}

		@Override
		public void ack() {
			ack(false);
		}

		public void ack(boolean async) {
			AcknowledgeRequest acknowledgeRequest = AcknowledgeRequest.newBuilder()
					.addAckIds(this.ackId)
					.setSubscription(this.subscriptionName)
					.build();

			if (async) {
				this.subscriberStub.acknowledgeCallable().futureCall(acknowledgeRequest);
			}
			else {
				this.subscriberStub.acknowledgeCallable().call(acknowledgeRequest);
			}
		}

		@Override
		public void nack() {
			nack(false);
		}

		public void nack(boolean async) {
			modifyAckDeadline(0, async);
		}

		public void modifyAckDeadline(int ackDeadlineSeconds) {
			modifyAckDeadline(ackDeadlineSeconds, false);
		}

		public void modifyAckDeadline(int ackDeadlineSeconds, boolean async) {
			ModifyAckDeadlineRequest modifyAckDeadlineRequest = ModifyAckDeadlineRequest.newBuilder()
					.setAckDeadlineSeconds(ackDeadlineSeconds)
					.addAckIds(this.ackId)
					.setSubscription(this.subscriptionName)
					.build();

			if (async) {
				this.subscriberStub.modifyAckDeadlineCallable().futureCall(modifyAckDeadlineRequest);
			}
			else {
				this.subscriberStub.modifyAckDeadlineCallable().call(modifyAckDeadlineRequest);
			}
		}
	}
}
