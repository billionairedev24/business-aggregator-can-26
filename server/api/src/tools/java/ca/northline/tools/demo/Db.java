package ca.northline.tools.demo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** A thin JDBC helper for the demo seed: positional parameters, typed for Postgres. */
final class Db {

    /** A text[] parameter. */
    record TextArray(List<String> values) {}

    private final Connection connection;

    Db(Connection connection) {
        this.connection = connection;
    }

    Connection connection() {
        return connection;
    }

    static TextArray array(Collection<String> values) {
        return new TextArray(List.copyOf(values));
    }

    static TextArray array(String... values) {
        return new TextArray(List.of(values));
    }

    int update(String sql, @Nullable Object... params) {
        try (var ps = prepare(sql, params)) {
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage() + "\n  in: " + sql.strip(), e);
        }
    }

    Optional<String> text(String sql, @Nullable Object... params) {
        try (var ps = prepare(sql, params);
                var rs = ps.executeQuery()) {
            return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage() + "\n  in: " + sql.strip(), e);
        }
    }

    long count(String sql, @Nullable Object... params) {
        return text(sql, params).map(Long::parseLong).orElse(0L);
    }

    interface RowReader<T> {
        T read(ResultSet rs) throws SQLException;
    }

    <T> List<T> list(String sql, RowReader<T> reader, @Nullable Object... params) {
        try (var ps = prepare(sql, params);
                var rs = ps.executeQuery()) {
            var out = new ArrayList<T>();
            while (rs.next()) {
                out.add(reader.read(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage() + "\n  in: " + sql.strip(), e);
        }
    }

    private PreparedStatement prepare(String sql, @Nullable Object... params) throws SQLException {
        var ps = connection.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            var p = params[i];
            switch (p) {
                case null -> ps.setNull(i + 1, Types.NULL);
                case Instant at -> ps.setTimestamp(i + 1, Timestamp.from(at));
                case LocalDate day -> ps.setObject(i + 1, day);
                case TextArray a -> ps.setArray(i + 1, connection.createArrayOf("text", a.values().toArray()));
                default -> ps.setObject(i + 1, p);
            }
        }
        return ps;
    }
}
