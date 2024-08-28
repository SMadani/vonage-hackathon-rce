package com.vonage.hackathon.rce;

import static com.vonage.hackathon.rce.ApplicationConfiguration.*;
import com.vonage.client.auth.camara.NetworkAuthResponseException;
import com.vonage.client.messages.*;
import com.vonage.client.messages.messenger.MessengerTextRequest;
import com.vonage.client.messages.sms.SmsTextRequest;
import com.vonage.client.messages.viber.ViberTextRequest;
import com.vonage.client.messages.whatsapp.WhatsappTextRequest;
import com.vonage.client.verify2.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import java.io.*;
import java.util.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.logging.Logger;

@Controller
public final class ApplicationController {
	private final Logger logger = Logger.getLogger("controller");

	private final int INITIAL_SIZE = 2;
	private final Set<String> blockedNumbers = new LinkedHashSet<>(INITIAL_SIZE);
	private final Map<UUID, String> pendingRegistrationRequests = new LinkedHashMap<>(INITIAL_SIZE);
	private final Map<String, Instant>
			pendingRegistrationTimestamps = new LinkedHashMap<>(INITIAL_SIZE),
			registeredNumbers = new LinkedHashMap<>(INITIAL_SIZE);

	@Autowired
	private ApplicationConfiguration configuration;

	private String standardWebhookResponse() {
		return "OK";
	}

	private synchronized void sendMessage(InboundMessage inbound, String text) {
		int threshold = 1000, length = text.length();
		if (length > threshold) {
			logger.info("Long message ("+length+" characters). Sending in parts...");
		}
		var parts = new String[length / threshold + 1];
		for (int i = 0; i < parts.length; i++) {
			parts[i] = text.substring(i * threshold, Math.min(length, (i + 1) * threshold));
		}

		var client = configuration.vonageClient.getMessagesClient().useSandboxEndpoint();
		MessageRequest.Builder<?, ?> builder = (
			switch (inbound.getChannel()) {
				case WHATSAPP -> WhatsappTextRequest.builder();
				case VIBER -> ViberTextRequest.builder();
				case MESSENGER -> MessengerTextRequest.builder();
				case SMS, MMS -> {
					client.useRegularEndpoint();
					yield SmsTextRequest.builder();
				}
			}
		).from(inbound.getTo()).to(inbound.getFrom());

		try {
			var textMethod = builder.getClass().getMethod("text", String.class);
			for (var part : parts) {
				textMethod.invoke(builder, part);
				logger.info("Message sent: " + client.sendMessage(builder.build()).getMessageUuid());
			}
		}
		catch (ReflectiveOperationException ex) {
			logger.warning("Could not send message: " + ex.getMessage());
			throw new IllegalStateException(ex);
		}
	}

	@ResponseBody
	@GetMapping("/_/health")
	public String health() {
		return standardWebhookResponse();
	}

	@ResponseBody
	@PostMapping(MESSAGE_STATUS_ENDPOINT)
	public String messageStatus(@RequestBody MessageStatus status) {
		logger.info("Received message status: "+status.toJson());
		return standardWebhookResponse();
	}

	private synchronized boolean checkRegistration(InboundMessage inbound) {
		var from = inbound.getFrom();
		if (!configuration.permittedNumbers.contains(from)) {
			if (!blockedNumbers.contains(from)) {
				logger.info("Number '"+from+"' is not on the list. Rejecting...");
				sendMessage(inbound, "You are not authorized to use this service.");
				blockedNumbers.add(from);
			}
			return false;
		}
		else if (blockedNumbers.remove(from)) {
			logger.info("Number '"+from+"' is now authorized.");
		}
		if (registeredNumbers.containsKey(from)) {
			logger.info("Number '"+from+"' is verified.");
			return true;
		}
		var pendingTimestamp = pendingRegistrationTimestamps.get(from);

		if (pendingTimestamp != null) {
			logger.info("Number '"+from+"' is pending registration.");
			var nextAttemptTime = pendingTimestamp.plus(2, ChronoUnit.MINUTES);
			if (pendingTimestamp.isAfter(nextAttemptTime)) {
				logger.info("Resending verification...");
				pendingRegistrationTimestamps.remove(from);
			}
			else {
				logger.info("Not enough time since last registration attempt.");
				var seconds = Instant.now().until(nextAttemptTime, ChronoUnit.SECONDS);
				sendMessage(inbound, "Please wait "+seconds+" seconds before trying again.");
				return false;
			}
		}
		else {
			logger.info("Unverified number '" + from + "'. Beginning registration...");
		}

		try {
			if (configuration.vonageClient.getSimSwapClient().checkSimSwap(from)) {
				logger.warning("SIM Swap detected for number: " + from);
			}
		}
		catch (NetworkAuthResponseException ex) {
			logger.warning(ex.getMessage());
		}

		var redirectUrl = configuration.serverUrl.resolve(COMPLETE_REGISTRATION_ENDPOINT).toString();
		var request = configuration.vonageClient.getVerify2Client().sendVerification(
				VerificationRequest.builder()
						.addWorkflow(new SilentAuthWorkflow(from, true, redirectUrl))
						.addWorkflow(new VoiceWorkflow(from))
						.brand(configuration.brand).build()
		);
		pendingRegistrationTimestamps.put(from, Instant.now());
		pendingRegistrationRequests.put(request.getRequestId(), from);
		sendMessage(inbound, "Please verify your number using mobile data: "+request.getCheckUrl());
		logger.info("Verification sent: "+request.getRequestId());
		return false;
	}

	@ResponseBody
	@GetMapping(COMPLETE_REGISTRATION_ENDPOINT)
	public String completeRegistrationExternal() {
		return """
			<!DOCTYPE html>
			<html lang="en">
			<head>
				<meta charset="UTF-8">
				<title>Convert Fragment</title>
				<script type="text/javascript">
					window.onload = function() {
						if (window.location.hash) {
							window.location.replace(window.location.href.replace('#', '/final?'));
						}
					};
				</script>
			</head>
			<body>
			<p>Redirecting...</p>
			</body>
			</html>
		""";
	}

	@ResponseBody
	@GetMapping(COMPLETE_REGISTRATION_ENDPOINT + "/final")
	public String completeRegistrationInternal(
			@RequestParam("request_id") UUID requestId,
			@RequestParam(required = false) String code,
			@RequestParam(value = "error_description", required = false) String errorText
	) {
		var v2c = configuration.vonageClient.getVerify2Client();
		String reason = null;
		if (code != null) {
			var check = v2c.checkVerificationCode(requestId, code);
			var status = check.getStatus();
			if (status == VerificationStatus.COMPLETED) {
				var verifiedNumber = pendingRegistrationRequests.remove(requestId);
				pendingRegistrationTimestamps.remove(verifiedNumber);
				registeredNumbers.put(verifiedNumber, Instant.now());
				logger.info("Registered number: " + verifiedNumber);
				return "<h1>Registration successful!</h1>";
			}
			else {
				reason = status.toString();
			}
		}
		else {
			reason = errorText;
		}
		logger.info("Silent Auth failed for request '"+requestId+"' with reason: "+reason);
		logger.info("Moving to next workflow...");
		var message = "Registration failed ("+reason+").";

		try {
			v2c.nextWorkflow(requestId);
		}
		catch (VerifyResponseException ex) {
			logger.warning("Failed to move to next workflow: "+ex.getMessage());
			return message;
		}
		return message + " Expect a phone call shortly...";
	}

	@ResponseBody
	@PostMapping(VERIFY_STATUS_ENDPOINT)
	public String verificationStatus(@RequestBody VerificationCallback callback) {
		logger.info("Received verification status: "+callback.toJson());
		return standardWebhookResponse();
	}

	@SuppressWarnings("StatementWithEmptyBody")
    @ResponseBody
	@PostMapping(INBOUND_MESSAGE_ENDPOINT)
	public String inboundMessage(@RequestBody InboundMessage inbound) throws IOException {
		logger.info("Received inbound message: "+inbound.toJson());
		if (checkRegistration(inbound)) {
			var command = inbound.getText();
			if (command != null && !command.isBlank()) {
				logger.info("Executing command: " + command);
				var process = new ProcessBuilder().redirectErrorStream(true)
						.command("sh", "-cr", command).start();

				String parsedOutput;
				try (var stream = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
					var output = new StringBuilder();
					for (String line; (line = stream.readLine()) != null; output.append(line).append("\n"));
					process.waitFor();
					parsedOutput = output.toString().trim();
					logger.info(parsedOutput);
				}
				catch (InterruptedException ex) {
					parsedOutput = "Process interrupted: " + ex.getMessage();
					logger.warning(parsedOutput);
				}

				sendMessage(inbound, parsedOutput.isBlank() ? "Exit value " + process.exitValue() : parsedOutput);
			}
			else {
				logger.warning("No command received.");
			}
		}
		return standardWebhookResponse();
	}
}
