package com.vonage.hackathon.rce;

import com.vonage.client.application.ApplicationResponseException;
import com.vonage.client.messages.sms.SmsTextRequest;
import com.vonage.client.numbers.*;
import static com.vonage.hackathon.rce.ApplicationConfiguration.*;
import com.vonage.client.application.Application;
import com.vonage.client.application.capabilities.Messages;
import com.vonage.client.application.capabilities.Verify;
import com.vonage.client.common.HttpMethod;
import com.vonage.client.common.Webhook;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.Arrays;
import java.util.logging.Logger;
import java.util.stream.Stream;

@Component
public class ApplicationStartup {
    private final Logger logger = Logger.getLogger("startup");

    @Autowired
    private ApplicationConfiguration configuration;

    private Webhook buildWebhook(String endpoint) {
        return Webhook.builder()
                .address(resolveEndpoint(endpoint).toString())
                .method(HttpMethod.POST).build();
    }

    private URI resolveEndpoint(String endpoint) {
        return configuration.serverUrl.resolve(endpoint);
    }

    private String getWelcomeMessage() {
        return "Server has started.";
    }

    @PostConstruct
    public void init() {
        var ac = configuration.vonageClient.getApplicationClient();
        var appIdStr = configuration.applicationId.toString();
        try {
            var existing = ac.getApplication(appIdStr);
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

        var nc = configuration.vonageClient.getNumbersClient();
        var fromNumber = Arrays.stream(nc.listNumbers(ListNumbersFilter.builder()
                    .applicationId(appIdStr).hasApplication(true).build()
            ).getNumbers())
                .filter(num -> Arrays.asList(num.getFeatures()).contains("SMS"))
                .map(OwnedNumber::getMsisdn).findFirst().orElseGet(() -> {
                    logger.info("No number with SMS capability assigned to application. Will search for one...");
                    try {
                        String number, countryCode;
                        var ownedResponse = nc.listNumbers(
                                ListNumbersFilter.builder().hasApplication(false).build()
                        );

                        if (ownedResponse.getCount() == 0 || ownedResponse.getNumbers().length == 0) {
                            logger.info("No vacant owned numbers available. Will search for purchasable numbers...");
                            var searchResponse = nc.searchNumbers(
                                    SearchNumbersFilter.builder().features(Feature.SMS).build()
                            );
                            if (searchResponse.getCount() == 0 || searchResponse.getNumbers().length == 0) {
                                String error = "No purchasable numbers available";
                                logger.warning(error);
                                throw new IllegalStateException(error);
                            }
                            else {
                                var first = searchResponse.getNumbers()[0];
                                number = first.getMsisdn();
                                countryCode = first.getCountry();
                                logger.info("Found available "+number+". Purchasing...");
                                nc.buyNumber(countryCode, number);
                                logger.info("Purchased number "+number);
                            }
                        }
                        else {
                            var first = ownedResponse.getNumbers()[0];
                            number = first.getMsisdn();
                            countryCode = first.getCountry();
                            logger.info("Found suitable owned number "+number);
                        }

                        logger.info("Assigning number "+number+" to application...");
                        nc.updateNumber(
                                UpdateNumberRequest.builder(number, countryCode)
                                        .moHttpUrl(resolveEndpoint(INBOUND_MESSAGE_ENDPOINT))
                                        .applicationId(appIdStr).build()
                        );
                        logger.info("Assigned number "+number+" to application "+appIdStr);
                        return number;
                    }
                    catch (NumbersResponseException ex) {
                        logger.warning("Could not buy and assign number: "+ex.getMessage());
                        throw ex;
                    }
                });

        var user = configuration.permittedNumbers.iterator().next();
        var sent = configuration.vonageClient.getMessagesClient().sendMessage(
                SmsTextRequest.builder().to(user).from(fromNumber).text(getWelcomeMessage()).build()
        );
        logger.info("Sent startup message to "+user+": "+sent.getMessageUuid());
    }
}
