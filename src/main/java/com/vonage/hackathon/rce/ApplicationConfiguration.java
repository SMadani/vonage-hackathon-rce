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
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.UUID;

@ConfigurationProperties(prefix = "vonage")
public class ApplicationConfiguration {
	//private static final URI EXTERNAL_IP_SERVICE_URL = URI.create("http://checkip.amazonaws.com/");

	//final HttpClient javaHttpClient = HttpClient.newHttpClient();
	final VonageClient vonageClient;
	final URI hostUrl;
	final String brand = "Vonage Hackathon Demo";
	int port;

	@Bean
	public WebServerFactoryCustomizer<ConfigurableWebServerFactory> webServerFactoryCustomizer() {
		return factory -> {
			getEnv("VCR_PORT").map(Integer::parseInt).ifPresent(factory::setPort);
			try {
				factory.setAddress(InetAddress.getByAddress(new byte[]{0,0,0,0}));
			}
			catch (UnknownHostException ex) {
				throw new IllegalStateException(ex);
			}
		};
	}

	record VonageCredentials(String apiKey, String apiSecret, String applicationId, String privateKey) {}

	private static Optional<String> getEnv(String env) {
		return Optional.ofNullable(System.getenv(env));
	}

	private static String getEnvWithAlt(String primary, String fallbackEnv) {
		return getEnv(primary).orElseGet(() -> System.getenv(fallbackEnv));
	}

	/*private String getExternalIp() {
		var request = HttpRequest.newBuilder().uri(EXTERNAL_IP_SERVICE_URL).GET().build();
		try {
			return javaHttpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
		}
		catch (IOException | InterruptedException ex) {
			throw new IllegalStateException("Could not get external IP", ex);
		}
	}*/

	@ConstructorBinding
    ApplicationConfiguration(VonageCredentials credentials) {
		var clientBuilder = VonageClient.builder();
		var apiKey = getEnvWithAlt("VONAGE_API_KEY", "VCR_API_ACCOUNT_ID");
		var apiSecret = getEnvWithAlt("VONAGE_API_SECRET", "VCR_API_ACCOUNT_SECRET");
		var applicationId = getEnvWithAlt("VONAGE_APPLICATION_ID", "VCR_API_APPLICATION_ID");
		var privateKey = getEnvWithAlt("VONAGE_PRIVATE_KEY_PATH", "VCR_PRIVATE_KEY");
		hostUrl = URI.create(getEnv("VONAGE_HACKATHON_SERVER_URL").map(
				self -> port > 80 ? self + ":" + port : self
		).orElseThrow());

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

		if (privateKey != null && applicationId != null) {
			try {
				var uuid = UUID.fromString(applicationId);
				assert uuid.version() > 0;
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
