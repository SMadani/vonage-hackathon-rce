package com.vonage.hackathon.rce;

import com.vonage.client.messages.InboundMessage;
import com.vonage.client.messages.MessageStatus;
import com.vonage.client.messages.sms.SmsTextRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.logging.Logger;

@Controller
public final class ApplicationController {
	private final Logger logger = Logger.getLogger("controller");

	String verifiedNumber = System.getenv("TO_NUMBER");

	@Autowired
	private ApplicationConfiguration configuration;

	private String standardWebhookResponse() {
		return "OK";
	}

	private void sendMessage(String from, String text) {
		int threshold = 950, length = text.length();
		if (length > threshold) {
			logger.info("Long message ("+length+" characters). Sending in parts...");
		}
		var parts = new String[length / threshold + 1];
		for (int i = 0; i < parts.length; i++) {
			parts[i] = text.substring(i * threshold, Math.min(length, (i + 1) * threshold));
		}

		var builder = SmsTextRequest.builder().from(from).to(verifiedNumber);

		for (var part : parts) {
			logger.info("Message sent: " + configuration.vonageClient.getMessagesClient()
				.sendMessage(builder.text(part).build()).getMessageUuid()
			);
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

	@SuppressWarnings("StatementWithEmptyBody")
    @ResponseBody
	@PostMapping("/webhooks/messages/inbound")
	public String inboundMessage(@RequestBody InboundMessage inbound) throws IOException {
		logger.info("Received inbound message: "+inbound.toJson());
		var command = inbound.getText();
		if (command != null && !command.isBlank()) {
			logger.info("Executing command: "+command);
			var process = new ProcessBuilder().redirectErrorStream(true)
					.command("sh", "-cr", command).start();

			String parsedOutput;

			try (var stream = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
				var output = new StringBuilder();
				for (String line; (line = stream.readLine()) != null; output.append(line).append("\n"));
				process.waitFor();
				parsedOutput = output.toString().trim();
				logger.info("Process output: " + parsedOutput);
			}
			catch (InterruptedException ex) {
				parsedOutput = "Process interrupted: "+ex.getMessage();
				logger.warning(parsedOutput);
			}

			sendMessage(inbound.getTo(), parsedOutput.isBlank() ?
					"Exit value "+process.exitValue() : parsedOutput
			);
		}
		else {
			logger.warning("No command received.");
		}
		return standardWebhookResponse();
	}
}
