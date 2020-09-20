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
package alfio.manager;

import alfio.model.EventAndOrganizationId;
import alfio.model.Ticket;
import alfio.model.modification.PollModification;
import alfio.model.poll.Poll;
import alfio.model.poll.PollWithOptions;
import alfio.model.result.ErrorCode;
import alfio.model.result.Result;
import alfio.repository.EventRepository;
import alfio.repository.PollRepository;
import alfio.repository.TicketRepository;
import alfio.util.Json;
import alfio.util.PinGenerator;
import lombok.AllArgsConstructor;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.IntStream;

import static java.util.Objects.requireNonNullElse;

@Component
@AllArgsConstructor
@Transactional
public class PollManager {
    private final PollRepository pollRepository;
    private final EventRepository eventRepository;
    private final TicketRepository ticketRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public Result<List<Poll>> getActiveForEvent(String eventName, String pin) {
        return validatePinAndEvent(pin, eventName)
            .flatMap(event -> Result.success(pollRepository.findActiveForEvent(event.getRight().getId())));
    }

    public Result<PollWithOptions> getSingleActiveForEvent(String eventName, Long id, String pin) {
        return validatePinAndEvent(pin, eventName)
            .flatMap(eventAndTicket -> {
                var optionalPoll = pollRepository.findSingleActiveForEvent(eventAndTicket.getLeft().getId(), id);
                if(optionalPoll.isEmpty()) {
                    return Result.error(ErrorCode.custom("not_found", ""));
                }
                return Result.success(new PollWithOptions(optionalPoll.get(), pollRepository.getOptionsForPoll(id)));
             });

    }

    public Result<Boolean> registerAnswer(String eventName, Long pollId, Long optionId, String pin) {

        if(pollId == null || optionId == null) {
            return Result.error(ErrorCode.custom("not_found", ""));
        }

        return validatePinAndEvent(pin, eventName)
            .flatMap(eventAndTicket -> {
                var event = eventAndTicket.getLeft();
                var ticket = eventAndTicket.getRight();
                Validate.isTrue(pollRepository.checkPollOption(optionId, pollId, event.getId()) == 1, "Invalid selection");
                Validate.isTrue(pollRepository.registerAnswer(pollId, optionId, ticket.getId(), event.getOrganizationId()) == 1, "Unexpected error while inserting answer");
                return Result.success(true);
            });
    }

    // admin
    public List<Poll> getAllForEvent(String eventName) {
        var eventOptional = eventRepository.findOptionalEventAndOrganizationIdByShortName(eventName);
        if(eventOptional.isEmpty()) {
            return List.of();
        }
        return pollRepository.findAllForEvent(eventOptional.get().getId());
    }

    public Optional<PollWithOptions> getSingleForEvent(Long pollId, String eventName) {
        return eventRepository.findOptionalEventAndOrganizationIdByShortName(eventName)
            .flatMap(event -> getSingleForEvent(pollId, event));
    }

    private Optional<PollWithOptions> getSingleForEvent(Long pollId, EventAndOrganizationId event) {
        return pollRepository.findSingleForEvent(event.getId(), Objects.requireNonNull(pollId))
            .map(poll -> new PollWithOptions(poll, pollRepository.getOptionsForPoll(pollId)));
    }

    public Optional<Long> createNewPoll(String eventName, PollModification form) {
        Validate.isTrue(form.isValid(false));
        return eventRepository.findOptionalEventAndOrganizationIdByShortName(eventName)
            .map(event -> {
                List<String> tags = form.isAccessRestricted() ? List.of(UUID.randomUUID().toString()) : List.of();
                var pollKey = pollRepository.insert(
                    form.getTitle(),
                    requireNonNullElse(form.getDescription(), Map.of()),
                    tags,
                    form.getOrder(),
                    event.getId(),
                    event.getOrganizationId()
                );
                Validate.isTrue(pollKey.getAffectedRowCount() == 1);
                if(form.getOptions().size() == 1) {
                    var option = form.getOptions().get(0);
                    pollRepository.insertOption(pollKey.getKey(),
                        option.getTitle(),
                        requireNonNullElse(option.getDescription(), Map.of()),
                        event.getOrganizationId());
                } else {
                    var parameterSources = form.getOptions().stream()
                        .map(option -> new MapSqlParameterSource("pollId", pollKey.getKey())
                            .addValue("title", Json.toJson(requireNonNullElse(option.getTitle(), Map.of())))
                            .addValue("description", Json.toJson(requireNonNullElse(option.getDescription(), Map.of())))
                            .addValue("organizationId", event.getOrganizationId()))
                        .toArray(MapSqlParameterSource[]::new);
                    int[] results = jdbcTemplate.batchUpdate(pollRepository.bulkInsertOptions(), parameterSources);
                    Validate.isTrue(IntStream.of(results).sum() == form.getOptions().size(), "Unexpected result from update.");
                }
                return pollKey.getKey();
            });
    }

    public Optional<PollWithOptions> updateStatus(Long pollId, String eventName, Poll.PollStatus newStatus) {
        Validate.isTrue(newStatus != Poll.PollStatus.DRAFT, "can't revert to draft");
        return eventRepository.findOptionalEventAndOrganizationIdByShortName(eventName)
            .flatMap(event -> {
                Validate.isTrue(pollRepository.updateStatus(newStatus, pollId, event.getId()) == 1, "Error while updating status");
                return getSingleForEvent(pollId, event);
            });
    }

    private Result<Pair<EventAndOrganizationId, Ticket>> validatePinAndEvent(String pin, String eventName) {
        var eventOptional = eventRepository.findOptionalEventAndOrganizationIdByShortName(eventName);
        return new Result.Builder<EventAndOrganizationId>()
            .checkPrecondition(eventOptional::isPresent, ErrorCode.EventError.NOT_FOUND)
            .checkPrecondition(() -> PinGenerator.isPinValid(pin), ErrorCode.custom("pin.invalid", ""))
            .build(eventOptional::get)
            .flatMap(event -> {
                var partialUuid = PinGenerator.pinToPartialUuid(pin);
                // find checkedIn ticket
                var tickets = ticketRepository.findByEventIdAndPartialUUIDForUpdate(event.getId(), partialUuid + "%", Ticket.TicketStatus.CHECKED_IN);
                int numResults = tickets.size();
                if(numResults != 1) {
                    return Result.error(ErrorCode.custom(numResults > 1 ? "pin.duplicate" : "pin.invalid", ""));
                }
                return Result.success(Pair.of(event, tickets.get(0)));
            });
    }

}