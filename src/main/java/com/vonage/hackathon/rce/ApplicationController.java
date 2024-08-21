package com.vonage.hackathon.rce;

import com.vonage.client.auth.camara.NetworkAuthResponseException;
import com.vonage.client.messages.InboundMessage;
import com.vonage.client.messages.MessageStatus;
import com.vonage.client.messages.sms.SmsTextRequest;
import com.vonage.client.verify2.SilentAuthWorkflow;
import com.vonage.client.verify2.VerificationCallback;
import com.vonage.client.verify2.VerificationRequest;
import com.vonage.client.verify2.VerificationStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

@Controller
public final class ApplicationController {
	private final Logger logger = Logger.getLogger("controller");
	private static final String COMPLETE_REGISTRATION_ENDPOINT = "/register/complete";

	private final Map<UUID, String> pendingRegistrations = new LinkedHashMap<>(2);
	private final Map<String, Instant> registeredNumbers = new LinkedHashMap<>(2);

	@Autowired
	private ApplicationConfiguration configuration;

	private String standardWebhookResponse() {
		return "OK";
	}

	private void sendMessage(String from, String to, String text) {
		int threshold = 1000, length = text.length();
		if (length > threshold) {
			logger.info("Long message ("+length+" characters). Sending in parts...");
		}
		var parts = new String[length / threshold + 1];
		for (int i = 0; i < parts.length; i++) {
			parts[i] = text.substring(i * threshold, Math.min(length, (i + 1) * threshold));
		}

		var client = configuration.vonageClient.getMessagesClient();
		var builder = SmsTextRequest.builder().from(from).to(to);
		for (var part : parts) {
			logger.info("Message sent: " + client.sendMessage(builder.text(part).build()).getMessageUuid());
		}
	}

	@ResponseBody
	@GetMapping("/_/health")
	public String health() {
		return standardWebhookResponse();
	}

	@ResponseBody
	@PostMapping("/webhooks/messages/status")
	public String messageStatus(@RequestBody MessageStatus status) {
		logger.info("Received message status: "+status.toJson());
		return standardWebhookResponse();
	}

	private boolean checkRegistration(InboundMessage inbound) {
		var from = inbound.getFrom();
		if (registeredNumbers.containsKey(from)) {
			logger.info("Number '"+from+"' is verified.");
			return true;
		}
		logger.info("Unknown number '"+from+"'. Beginning registration...");

		try {
			if (configuration.vonageClient.getSimSwapClient().checkSimSwap(from)) {
				logger.warning("SIM Swap detected for number: " + from);
			}
		}
		catch (NetworkAuthResponseException ex) {
			logger.warning(ex.getMessage());
		}

		var redirectUrl = configuration.hostUrl.resolve(COMPLETE_REGISTRATION_ENDPOINT).toString();
		var request = configuration.vonageClient.getVerify2Client().sendVerification(
				VerificationRequest.builder()
						.addWorkflow(new SilentAuthWorkflow(from, true, redirectUrl))
						.brand(configuration.brand).build()
		);
		pendingRegistrations.put(request.getRequestId(), from);
		sendMessage(inbound.getTo(), from,
				"Please verify your number using mobile data: "+request.getCheckUrl()
		);
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
	public String completeRegistrationInternal(@RequestParam("request_id") UUID requestId, @RequestParam String code) {
		var check = configuration.vonageClient.getVerify2Client().checkVerificationCode(requestId, code);
		var status = check.getStatus();
		if (status == VerificationStatus.COMPLETED) {
			var verifiedNumber = pendingRegistrations.remove(requestId);
			registeredNumbers.put(verifiedNumber, Instant.now());
			logger.info("Registered number: " + verifiedNumber);
			return "Registration successful!";
		}
		else return "Registration failed. Status: " + status;
	}

	@ResponseBody
	@PostMapping("/webhooks/verify/status")
	public String verificationStatus(@RequestBody VerificationCallback callback) {
		logger.info("Received verification status: "+callback.toJson());
		return standardWebhookResponse();
	}

	@SuppressWarnings("StatementWithEmptyBody")
    @ResponseBody
	@PostMapping("/webhooks/messages/inbound")
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
					for (String line; (line = stream.readLine()) != null; output.append(line).append("\n")) ;
					process.waitFor();
					parsedOutput = output.toString().trim();
					logger.info("Process output: " + parsedOutput);
				}
				catch (InterruptedException ex) {
					parsedOutput = "Process interrupted: " + ex.getMessage();
					logger.warning(parsedOutput);
				}

				sendMessage(inbound.getTo(), inbound.getFrom(), parsedOutput.isBlank() ?
						"Exit value " + process.exitValue() : parsedOutput
				);
			}
			else {
				logger.warning("No command received.");
			}
		}
		return standardWebhookResponse();
	}
}
