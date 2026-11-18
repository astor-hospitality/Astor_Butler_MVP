package museon_online.astor_butler.domain.auction;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ArtAuctionRepository {

    private final JdbcTemplate jdbcTemplate;

    public List<ArtAuctionLot> findActiveLots(String venueCode) {
        return jdbcTemplate.query("""
                SELECT l.*
                FROM art_auction_lots l
                JOIN art_auction_events e ON e.id = l.auction_event_id
                WHERE e.venue_code = ?
                  AND e.status = 'ACTIVE'
                  AND l.status = 'ACTIVE'
                  AND (e.starts_at IS NULL OR e.starts_at <= CURRENT_TIMESTAMP)
                  AND (e.ends_at IS NULL OR e.ends_at >= CURRENT_TIMESTAMP)
                ORDER BY l.title
                """,
                lotMapper(),
                normalizeVenue(venueCode)
        );
    }

    public Optional<ArtAuctionLot> findLot(Long id) {
        List<ArtAuctionLot> result = jdbcTemplate.query("""
                SELECT *
                FROM art_auction_lots
                WHERE id = ?
                """,
                lotMapper(),
                id
        );
        return result.stream().findFirst();
    }

    public Optional<ArtAuctionLot> findBiddableLot(Long id) {
        List<ArtAuctionLot> result = jdbcTemplate.query("""
                SELECT l.*
                FROM art_auction_lots l
                JOIN art_auction_events e ON e.id = l.auction_event_id
                WHERE l.id = ?
                  AND e.status = 'ACTIVE'
                  AND l.status = 'ACTIVE'
                  AND (e.starts_at IS NULL OR e.starts_at <= CURRENT_TIMESTAMP)
                  AND (e.ends_at IS NULL OR e.ends_at >= CURRENT_TIMESTAMP)
                """,
                lotMapper(),
                id
        );
        return result.stream().findFirst();
    }

    public Optional<ArtAuctionLot> findDefaultActiveLot(String venueCode) {
        List<ArtAuctionLot> result = jdbcTemplate.query("""
                SELECT l.*
                FROM art_auction_lots l
                JOIN art_auction_events e ON e.id = l.auction_event_id
                WHERE e.venue_code = ?
                  AND e.status = 'ACTIVE'
                  AND l.status = 'ACTIVE'
                  AND (e.starts_at IS NULL OR e.starts_at <= CURRENT_TIMESTAMP)
                  AND (e.ends_at IS NULL OR e.ends_at >= CURRENT_TIMESTAMP)
                ORDER BY CASE WHEN l.metadata_json ->> 'default' = 'true' THEN 0 ELSE 1 END, l.title
                LIMIT 1
                """,
                lotMapper(),
                normalizeVenue(venueCode)
        );
        return result.stream().findFirst();
    }

    public ArtAuctionBid createBidDraft(ArtAuctionBidCommand command, ArtAuctionLot lot) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO art_auction_bids (
                    lot_id, chat_id, telegram_user_id, user_id, status, source, amount_minor,
                    currency, bidder_name, guest_comment, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, 'TELEGRAM', ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class,
                lot.id(),
                command.chatId(),
                command.telegramUserId(),
                command.userId(),
                ArtAuctionBidStatus.AWAITING_GUEST_CONFIRMATION.name(),
                command.amountMinor(),
                currency(command.currency()),
                blankToNull(command.bidderName()),
                blankToNull(command.guestComment())
        );
        return findBid(id).orElseThrow();
    }

    public Optional<ArtAuctionBid> findBid(Long id) {
        List<ArtAuctionBid> result = jdbcTemplate.query("""
                SELECT *
                FROM art_auction_bids
                WHERE id = ?
                """,
                bidMapper(),
                id
        );
        return result.stream().findFirst();
    }

    public Optional<ArtAuctionBid> findLatestAwaitingGuestConfirmation(Long chatId) {
        List<ArtAuctionBid> result = jdbcTemplate.query("""
                SELECT *
                FROM art_auction_bids
                WHERE chat_id = ?
                  AND status = ?
                ORDER BY created_at DESC
                LIMIT 1
                """,
                bidMapper(),
                chatId,
                ArtAuctionBidStatus.AWAITING_GUEST_CONFIRMATION.name()
        );
        return result.stream().findFirst();
    }

    public List<ArtAuctionBid> findBidsByChatId(Long chatId, int limit) {
        return jdbcTemplate.query("""
                SELECT *
                FROM art_auction_bids
                WHERE chat_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """,
                bidMapper(),
                chatId,
                limit
        );
    }

    public ArtAuctionBid updateBidStatus(Long id, ArtAuctionBidStatus status) {
        jdbcTemplate.update("""
                UPDATE art_auction_bids
                SET status = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                status.name(),
                id
        );
        return findBid(id).orElseThrow();
    }

    private RowMapper<ArtAuctionLot> lotMapper() {
        return (rs, rowNum) -> new ArtAuctionLot(
                rs.getLong("id"),
                rs.getLong("auction_event_id"),
                rs.getString("lot_code"),
                rs.getString("title"),
                rs.getString("artist_name"),
                rs.getString("description"),
                ArtAuctionLotStatus.valueOf(rs.getString("status")),
                nullableLong(rs, "starting_price_minor"),
                nullableLong(rs, "min_step_minor"),
                rs.getString("currency"),
                rs.getString("media_asset_code"),
                rs.getString("metadata_json"),
                instant(rs, "created_at"),
                instant(rs, "updated_at")
        );
    }

    private RowMapper<ArtAuctionBid> bidMapper() {
        return (rs, rowNum) -> new ArtAuctionBid(
                rs.getLong("id"),
                rs.getLong("lot_id"),
                rs.getLong("chat_id"),
                nullableLong(rs, "telegram_user_id"),
                nullableLong(rs, "user_id"),
                ArtAuctionBidStatus.valueOf(rs.getString("status")),
                rs.getString("source"),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                rs.getString("bidder_name"),
                rs.getString("guest_comment"),
                rs.getString("payment_external_id"),
                instant(rs, "created_at"),
                instant(rs, "updated_at")
        );
    }

    private String normalizeVenue(String venueCode) {
        return venueCode == null || venueCode.isBlank() ? "AERIS" : venueCode.trim().toUpperCase();
    }

    private String currency(String currency) {
        return currency == null || currency.isBlank() ? "RUB" : currency.trim().toUpperCase();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Instant instant(ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Long nullableLong(ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
