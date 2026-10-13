package museon_online.astor_butler.domain.journey;

import java.util.List;

/** The guest's last lunch, as dish codes of today's menu (one entry per portion) and titles to show. */
public record LastLunch(List<String> dishCodes, List<String> titles) {
    public LastLunch {
        dishCodes = List.copyOf(dishCodes);
        titles = List.copyOf(titles);
    }
}
