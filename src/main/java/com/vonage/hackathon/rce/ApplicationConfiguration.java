package com.vonage.hackathon.rce;

import com.vonage.client.VonageClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.web.server.ConfigurableWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@ConfigurationProperties(prefix = "vonage")
public class ApplicationConfiguration {
	static final String
			INBOUND_MESSAGE_ENDPOINT = "/webhooks/messages/inbound",
			MESSAGE_STATUS_ENDPOINT = "/webhooks/messages/status",
			VERIFY_STATUS_ENDPOINT = "/webhooks/verify/status",
			COMPLETE_REGISTRATION_ENDPOINT = "/register/complete";

	final VonageClient vonageClient;
	final URI serverUrl;
	final UUID applicationId;
	final String brand = "Hackathon";
	final Set<String> permittedNumbers;
	final int port;

	@Bean
	public WebServerFactoryCustomizer<ConfigurableWebServerFactory> webServerFactoryCustomizer() {
		return factory -> {
			factory.setPort(port);
			try {
				factory.setAddress(InetAddress.getByAddress(new byte[]{0,0,0,0}));
			}
			catch (UnknownHostException ex) {
				throw new IllegalStateException(ex);
			}
		};
	}

	record VonageCredentials(String apiKey, String apiSecret, String applicationId, String privateKey) {}

	record ApplicationParameters(URI serverUrl, Integer port, String[] permittedNumbers) {}

	private static Optional<String> getEnv(String env) {
		return Optional.ofNullable(System.getenv(env));
	}

	private static String getEnvWithAlt(String primary, String fallbackEnv) {
		return getEnv(primary).orElseGet(() -> System.getenv(fallbackEnv));
	}

	private String getExternalIp() {
		try (var javaHttpClient = HttpClient.newHttpClient()) {
			return javaHttpClient.send(
					HttpRequest.newBuilder().uri(URI.create("http://checkip.amazonaws.com/")).GET().build(),
					HttpResponse.BodyHandlers.ofString()
			).body();
		}
		catch (IOException | InterruptedException ex) {
			throw new IllegalStateException("Could not get external IP", ex);
		}
	}

	@ConstructorBinding
    ApplicationConfiguration(VonageCredentials credentials, ApplicationParameters parameters) {
        this.port = parameters != null && parameters.port() != null && parameters.port() > 80 ?
				parameters.port() : getEnv("VCR_PORT").map(Integer::parseInt).orElse(8080);

		var serverUrlEnvName = "VONAGE_HACKATHON_SERVER_URL";
        serverUrl = parameters != null && parameters.serverUrl() != null ? parameters.serverUrl() :
				URI.create(getEnv(serverUrlEnvName).orElseThrow(
						() -> new IllegalStateException(serverUrlEnvName+" not set.")
				));

        permittedNumbers = parameters != null && parameters.permittedNumbers() != null &&
				parameters.permittedNumbers.length > 0 ? Set.of(parameters.permittedNumbers()) :
				Set.of(getEnv("TO_NUMBER").orElseThrow(() -> new IllegalStateException("TO_NUMBER not set.")));

		var clientBuilder = VonageClient.builder();
		var apiKey = getEnvWithAlt("VONAGE_API_KEY", "VCR_API_ACCOUNT_ID");
		var apiSecret = getEnvWithAlt("VONAGE_API_SECRET", "VCR_API_ACCOUNT_SECRET");
		var applicationId = getEnvWithAlt("VONAGE_APPLICATION_ID", "VCR_API_APPLICATION_ID");
		var privateKey = getEnvWithAlt("VONAGE_PRIVATE_KEY_PATH", "VCR_PRIVATE_KEY");

		if (credentials != null) {
			if (credentials.apiKey != null && !credentials.apiKey.isEmpty()) {
				apiKey = credentials.apiKey;
			}
			if (credentials.apiSecret != null && !credentials.apiSecret.isEmpty()) {
				apiSecret = credentials.apiSecret;
			}
			if (credentials.applicationId != null && !credentials.applicationId.isEmpty()) {
				applicationId = credentials.applicationId;
			}
			if (credentials.privateKey != null && !credentials.privateKey.isEmpty()) {
				privateKey = credentials.privateKey;
			}
		}

		if (applicationId == null) {
			throw new IllegalStateException("Application ID not set.");
		}
		this.applicationId = UUID.fromString(applicationId);

		if (privateKey != null) {
			try {
				if (privateKey.startsWith("-----BEGIN PRIVATE KEY-----")) {
					clientBuilder.privateKeyContents(privateKey.getBytes());
				}
				else {
					clientBuilder.privateKeyPath(Paths.get(privateKey));
				}
				clientBuilder.applicationId(applicationId);
			}
			catch (InvalidPathException ipx) {
				System.err.println("Invalid path or private key: "+privateKey);
			}
			catch (IllegalArgumentException iax) {
				System.err.println("Invalid application ID: "+applicationId);
			}
		}
		if (apiKey != null && apiKey.length() >= 7 && apiSecret != null && apiSecret.length() >= 16) {
			clientBuilder.apiKey(apiKey).apiSecret(apiSecret);
		}

		vonageClient = clientBuilder.build();
	}
}
