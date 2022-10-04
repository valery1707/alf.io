/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.manager.wallet;

import alfio.manager.system.ConfigurationManager;
import alfio.model.*;
import alfio.model.system.ConfigurationKeys;
import alfio.model.user.Organization;
import alfio.repository.EventDescriptionRepository;
import alfio.repository.EventRepository;
import alfio.repository.TicketCategoryRepository;
import alfio.repository.TicketRepository;
import alfio.repository.user.OrganizationRepository;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateKey;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static alfio.config.Initializer.*;
import static alfio.model.system.ConfigurationKeys.*;

@Component
@AllArgsConstructor
@Log4j2
public class GoogleWalletManager {

    private static final String GOOGLE_WALLET_PASS = "GoogleWalletPass";
    private final EventRepository eventRepository;
    private final OrganizationRepository organizationRepository;
    private final ConfigurationManager configurationManager;
    private final EventDescriptionRepository eventDescriptionRepository;
    private final TicketCategoryRepository ticketCategoryRepository;
    private final TicketRepository ticketRepository;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Environment environment;

    public Optional<Pair<EventAndOrganizationId, Ticket>> validateTicket(String eventName, String ticketUuid) {
        var eventOptional = eventRepository.findOptionalEventAndOrganizationIdByShortName(eventName);
        if (eventOptional.isEmpty()) {
            log.trace("event {} not found", eventName);
            return Optional.empty();
        }

        var event = eventOptional.get();
        return ticketRepository.findOptionalByUUID(ticketUuid)
            .filter(t -> t.getEventId() == event.getId())
            .map(t -> Pair.of(event, t));
    }

    public String createAddToWalletUrl(Ticket ticket, EventAndOrganizationId event) {
        Map<ConfigurationKeys, String> passConf = getConfigurationKeys(event);
        if (!passConf.isEmpty()) {
            return buildWalletPassUrl(ticket, eventRepository.findById(event.getId()), passConf);
        } else {
            throw new RuntimeException("Google Wallet integration is not enabled.");
        }
    }

    private Map<ConfigurationKeys, String> getConfigurationKeys(EventAndOrganizationId event) {
        var conf = configurationManager.getFor(Set.of(
                ENABLE_WALLET,
                WALLET_ISSUER_IDENTIFIER,
                WALLET_SERVICE_ACCOUNT_KEY,
                WALLET_OVERWRITE_PREVIOUS_CLASSES_AND_EVENTS,
                BASE_URL),
            event.getConfigurationLevel());

        if (!conf.get(ENABLE_WALLET).getValueAsBooleanOrDefault()) {
            return Map.of();
        }
        var configValues = Map.of(
            WALLET_ISSUER_IDENTIFIER, conf.get(WALLET_ISSUER_IDENTIFIER).getValue(),
            WALLET_SERVICE_ACCOUNT_KEY, conf.get(WALLET_SERVICE_ACCOUNT_KEY).getValue(),
            WALLET_OVERWRITE_PREVIOUS_CLASSES_AND_EVENTS, conf.get(WALLET_OVERWRITE_PREVIOUS_CLASSES_AND_EVENTS).getValue(),
            BASE_URL, conf.get(BASE_URL).getValue());

        if (configValues.values().stream().anyMatch(Optional::isEmpty)) {
            return Map.of();
        }

        return configValues
            .entrySet()
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().orElseThrow()));
    }

    private String buildWalletPassUrl(Ticket ticket,
                                      Event event,
                                      Map<ConfigurationKeys, String> config) {
        String baseUrl = config.get(BASE_URL);
        String issuerId = config.get(WALLET_ISSUER_IDENTIFIER);
        String serviceAccountKey = config.get(WALLET_SERVICE_ACCOUNT_KEY);
        boolean overwritePreviousClassesAndEvents = Boolean.parseBoolean(config.get(WALLET_OVERWRITE_PREVIOUS_CLASSES_AND_EVENTS));

        String eventDescription = eventDescriptionRepository.findDescriptionByEventIdTypeAndLocale(event.getId(), EventDescription.EventDescriptionType.DESCRIPTION, ticket.getUserLanguage()).orElse("");
        TicketCategory category = ticketCategoryRepository.getById(ticket.getCategoryId());
        var ticketValidityStart = Optional.ofNullable(category.getTicketValidityStart(event.getZoneId())).orElse(event.getBegin());
        var ticketValidityEnd = Optional.ofNullable(category.getTicketValidityEnd(event.getZoneId())).orElse(event.getEnd());

        EventTicketClass.LatitudeLongitudePoint latitudeLongitudePoint = null;
        if (event.getLatitude() != null && event.getLongitude() != null) {
            latitudeLongitudePoint = EventTicketClass.LatitudeLongitudePoint.of(Double.parseDouble(event.getLatitude()), Double.parseDouble(event.getLongitude()));
        }

        var eventTicketClass = EventTicketClass.builder()
            .id(String.format("%s.%s-class-%d", issuerId, walletIdPrefix(), category.getId()))
            .eventOrGroupingId(Integer.toString(event.getId()))
            .logoUri(baseUrl + "/file/" + event.getFileBlobId())
            .eventName(eventDescription)
            .description(event.getDisplayName())
            .venue(event.getLocation())
            .location(latitudeLongitudePoint)
            .ticketType(category.getName())
            .start(ticketValidityStart)
            .end(ticketValidityEnd)
            .build();

        var eventTicketObject = EventTicketObject.builder()
            .id(String.format("%s.%s-object-%s", issuerId, walletIdPrefix(), ticket.getUuid()))
            .classId(eventTicketClass.getId())
            .ticketHolderName(ticket.getFullName())
            .ticketNumber(ticket.getUuid())
            .barcode(ticket.ticketCode(event.getPrivateKey()))
            .build();

        GoogleCredentials credentials = null;
        try {
            credentials = GoogleCredentials
                .fromStream(new ByteArrayInputStream(serviceAccountKey.getBytes(StandardCharsets.UTF_8)))
                .createScoped(Collections.singleton("https://www.googleapis.com/auth/wallet_object.issuer"));
        } catch (IOException e) {
            throw new GoogleWalletException("Unable to retrieve Service Account Credentials from configuration", e);
        }

        createEventClass(credentials, eventTicketClass, overwritePreviousClassesAndEvents);
        String eventObjectId = createEventObject(credentials, eventTicketObject, overwritePreviousClassesAndEvents);

        return generateWalletPassUrl(credentials, eventObjectId, baseUrl);
    }

    String generateWalletPassUrl(GoogleCredentials credentials, String eventObjectId, String url) {
        var objectIdMap = Map.of("id", eventObjectId);
        var payload = Map.of("genericObjects", List.of(objectIdMap));
        var claims = Map.of(
            "iss", ((ServiceAccountCredentials) credentials).getClientEmail(),
            "aud", "google",
            "origins", List.of(url),
            "typ", "savetowallet",
            "payload", payload
        );

        Algorithm algorithm = Algorithm.RSA256(
            null,
            (RSAPrivateKey) ((ServiceAccountCredentials) credentials).getPrivateKey());
        String token = JWT.create()
            .withPayload(claims)
            .sign(algorithm);
        return String.format("https://pay.google.com/gp/v/save/%s", token);
    }

    private String createEventClass(GoogleCredentials credentials, EventTicketClass eventTicketClass, boolean overwritePreviousClassesAndEvents) {
        return createOnWallet(EventTicketClass.WALLET_URL, credentials, eventTicketClass, overwritePreviousClassesAndEvents);
    }

    private String createEventObject(GoogleCredentials credentials, EventTicketObject eventTicketObject, boolean overwritePreviousClassesAndEvents) {
        return createOnWallet(EventTicketObject.WALLET_URL, credentials, eventTicketObject, overwritePreviousClassesAndEvents);
    }

    private String createOnWallet(String uri, GoogleCredentials credentials, WalletEntity entity, boolean overwritePreviousClassesAndEvents) {
        try {
            URI uriWithId = URI.create(String.format("%s/%s", uri, entity.getId()));
            HttpRequest getRequest = HttpRequest.newBuilder()
                .uri(uriWithId)
                .header("Authorization", String.format("Bearer %s", credentials.refreshAccessToken().getTokenValue()))
                .GET()
                .build();
            log.debug("GET Request: {}", getRequest);

            HttpResponse<String> getResponse = httpClient.send(getRequest, HttpResponse.BodyHandlers.ofString());
            log.debug("GET Response: {}", getResponse);

            if (getResponse.statusCode() == 404) {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .header("Authorization", String.format("Bearer %s", credentials.refreshAccessToken().getTokenValue()));
                if (overwritePreviousClassesAndEvents) {
                    builder = builder
                        .uri(uriWithId)
                        .PUT(HttpRequest.BodyPublishers.ofString(entity.build(objectMapper)));
                } else {
                    builder
                        .uri(URI.create(uri))
                        .POST(HttpRequest.BodyPublishers.ofString(entity.build(objectMapper)));
                }
                HttpRequest postOrPutRequest = builder.build();
                log.debug("POST or PUT Request: {}", postOrPutRequest);
                HttpResponse<String> postOrPutResponse = httpClient.send(postOrPutRequest, HttpResponse.BodyHandlers.ofString());
                log.debug("POST or PUT Response: {}", postOrPutResponse);
            }
            return entity.getId();
        } catch (IOException | InterruptedException e) {
            throw new GoogleWalletException("Error while communication with the Google Wallet API", e);
        }
    }

    private String walletIdPrefix() {
        return Stream.of(PROFILE_DEMO, PROFILE_DEV, PROFILE_LIVE)
            .filter(p -> environment.acceptsProfiles(Profiles.of(p)))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("No suitable Spring Profile found to create a Wallet ID prefix for classes and objects. Must have one of PROFILE_DEMO, PROFILE_DEV, or PROFILE_LIVE"));
    }

}
