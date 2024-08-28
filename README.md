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

