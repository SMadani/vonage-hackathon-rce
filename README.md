# Vonage Hackathon Remote Code Execution Demo

![Java](https://img.shields.io/badge/java-21%2B-red)
[![Build Status](https://github.com/Vonage-Community/sample-serversdk-java-springboot/actions/workflows/build.yml/badge.svg)](https://github.com/SMadani/vonage-hackathon-rce/actions/workflows/build.yml")
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
![SLOC](https://sloc.xyz/github/SMadani/vonage-hackathon-rce/)

This repo demonstrates how you can use the [Messages API](https://developer.vonage.com/en/messages/technical-details)
to execute shell commands on your server.
It uses the [Verify API](https://developer.vonage.com/en/verify/technical-details) to authenticate users before
allowing them to run commands. Only pre-approved numbers can sign up to use the service.

It is built using the [Vonage Java Server SDK](https://github.com/Vonage/vonage-java-sdk) and Spring Boot 3.

## Workflow
Here's the application logic in a nutshell:

1. Create Vonage account and application, downloading the private key to the server.
2. Configure the server environment to be able to accept requests.
   - Start ngrok or tunnelling service / alternatively enable DMZ (basically ensure computer is accessible to internet) on a chosen port.
   - Set environment variables required for the application.
   - Install Java 21 and Maven if not already present.
3. Start the application (`mvn spring-boot:run`).
    - Application updates Vonage Application's webhook URLs automatically using Application API.
    - The sender number is chosen from the application. If one is not assigned, an appropriate one with SMS capabilities is searched for and purchased and linked to the application, unless an existing vacant number exists in the account, in which case that will be used instead.
4. Authenticate the user:
   - Server sends message to number configured in 3b to notify that it's ready.
   - User replies with anything when they are ready to begin the process.
   - Server does a SIM Swap check if available. If this is not available due to network / application restrictions the result is skipped. If the SIM Swap is available and comes back true, it is flagged and number is refused.
   - The server initiates Silent Authentication by sending a check URL to the user.
   - The user follows the link on mobile data to verify the number.
   - If this is successful, the number is now allowed to use the application
   - If the auth is unsuccessful, it moves to a backup workflow where the user is voice called instead with a PIN. This then needs to be texted to the application to authenticate.
   - If the user sends another text before auth is complete, the server informs them of the remaining timeout before another auth attempt can be initiated.
   - If an unknown number attempts to authenticate, it is blocklisted and no further replies will be sent after informing them they are unauthorised.
5. Process inbound messages
   - User sends a command they want to run on the system to the registered application's number via SMS, WhatsApp, Viber or Messenger.
   - If user's number is authenticated, this command is executed in a restricted shell.
   - The output of the command is sent back to the user over the same channel they used to send the message.

## Pre-requisites
You will need Java Development Kit 21 or later to run this demo.
Installation instructions can be found [here for Temurin JDKs](https://adoptium.net/en-GB/installation/) or
[here for Oracle JDK 21](https://docs.oracle.com/en/java/javase/21/install/overview-jdk-installation.html).

You will also need to expose your local server to the internet using a service like [ngrok](https://ngrok.com/).
You need to have a Vonage application and downloaded the private key associated with it.
See the [Getting Started guide](https://developer.vonage.com/en/getting-started/overview) for an overview.
You can view your applications [on the Vonage dashboard](https://dashboard.nexmo.com/applications).

## Configuration
All the parameters required to run the demo can be provided through environment variables. These are as follows:

- `VONAGE_API_KEY`: Vonage account API key.
- `VONAGE_API_SECRET`: Vonage account API secret.
- `VONAGE_APPLICATION_ID`: Vonage application UUID.
- `VONAGE_PRIVATE_KEY_PATH`: Absolute path to the private key associated with your Vonage application.
- `TO_NUMBER`: Client phone number that is allowed to run commands.
- `VONAGE_HACKATHON_SERVER_URL`: External URL of the server. Typically, this would be an `ngrok.app` URL.
- `VCR_PORT`: Port to run the demo on. By default, this is `8080`.

## Build & Run
If you have [IntelliJ IDEA](https://www.jetbrains.com/idea/) installed, you can import this project
and run it through the IDE, where the entry point is the `Application` class
(src/main/java/com/vonage/hackathon/rce/Application.java).

To run the demo standalone from the command line, do `mvn install spring-boot:run`.

For convenience, you can add the following to your environment variables (`~/.bashrc` or `~/.zshrc`):

```shell
export VCR_PORT=9080
export NGROK_URL="https://[id].ngrok.app"
export VONAGE_HACKATHON_SERVER_URL=$NGROK_URL
function ngrokUrl() {
    local url=$(curl -s localhost:4040/api/tunnels | jq -r '.tunnels[0].public_url')
    sed -i '' "s|\(^export NGROK_URL=\).*|\1\"$url\"|" ~/.zshrc
    source ~/.zshrc && echo $NGROK_URL
}
```

You can then invoke `ngrokUrl` to update the `NGROK_URL` environment variable after calling `ngrok http $VCR_PORT`.
On startup, the application will automatically update your Vonage application's webhooks to point to this URL.

