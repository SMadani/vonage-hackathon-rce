package com.vonage.hackathon.rce;

import com.vonage.client.VonageClient;
import com.vonage.client.application.ApplicationResponseException;
import static com.vonage.hackathon.rce.ApplicationConfiguration.*;
import com.vonage.client.application.Application;
import com.vonage.client.application.capabilities.Messages;
import com.vonage.client.application.capabilities.Verify;
import com.vonage.client.common.HttpMethod;
import com.vonage.client.common.Webhook;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.logging.Logger;

@Component
public class ApplicationStartup {
    private final Logger logger = Logger.getLogger("startup");

    @Autowired
    private ApplicationConfiguration configuration;

    private Webhook buildWebhook(String endpoint) {
        return Webhook.builder()
                .address(configuration.hostUrl.resolve(endpoint).toString())
                .method(HttpMethod.POST).build();
    }

    @PostConstruct
    public void init() {
        var ac = configuration.vonageClient.getApplicationClient();
        try {
            var existing = ac.getApplication(configuration.applicationId.toString());
            var application = ac.updateApplication(
                    Application.builder(existing)
                            .addCapability(Verify.builder()
                                    .addWebhook(Webhook.Type.STATUS, buildWebhook(VERIFY_STATUS_ENDPOINT)).build()
                            ).addCapability(Messages.builder()
                                    .addWebhook(Webhook.Type.INBOUND, buildWebhook(INBOUND_MESSAGE_ENDPOINT))
                                    .addWebhook(Webhook.Type.STATUS, buildWebhook(MESSAGE_STATUS_ENDPOINT))
                                    .build()
                            ).build()
            );
            assert application != null;
        }
        catch (ApplicationResponseException ex) {
            logger.warning("Failed to update application: "+ex.getMessage());
            throw ex;
        }
    }
}
