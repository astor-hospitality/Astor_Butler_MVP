package museon_online.astor_butler.domain.lunch;

import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationStatus;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

/** Lunch offers for tests. The dishes and prices are made up for the tests and belong to no real venue. */
public final class BusinessLunchFixtures {

    private static final List<DayOfWeek> WEEKDAYS = List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);

    private BusinessLunchFixtures() {
    }

    /** A venue with a real choice in every course and a price on every set. */
    public static BusinessLunchOffer fullMenu() {
        return new BusinessLunchOffer(
                "AERIS",
                "AERIS",
                true,
                WEEKDAYS,
                LocalTime.of(12, 0),
                LocalTime.of(16, 0),
                90,
                "чай или морс",
                List.of(
                        new BusinessLunchOffer.Course("SALAD", "Салат", List.of(
                                new BusinessLunchOffer.Dish("GREEK", "Греческий салат"),
                                new BusinessLunchOffer.Dish("BEETROOT", "Салат со свеклой"))),
                        new BusinessLunchOffer.Course("SOUP", "Суп", List.of(
                                new BusinessLunchOffer.Dish("BROTH", "Куриный бульон"),
                                new BusinessLunchOffer.Dish("PUMPKIN", "Крем-суп из тыквы"))),
                        new BusinessLunchOffer.Course("MAIN", "Горячее", List.of(
                                new BusinessLunchOffer.Dish("PASTA", "Паста с томатами"),
                                new BusinessLunchOffer.Dish("CHICKEN", "Курица с рисом")))
                ),
                sets(590, 650, 690)
        );
    }

    /** A venue whose menu is not agreed yet: one dish of the day per course, no prices. */
    public static BusinessLunchOffer dishOfTheDay() {
        return new BusinessLunchOffer(
                "SIMPLE",
                "Simple",
                false,
                WEEKDAYS,
                LocalTime.of(12, 0),
                LocalTime.of(16, 0),
                0,
                "",
                List.of(
                        new BusinessLunchOffer.Course("SALAD", "Салат", List.of(new BusinessLunchOffer.Dish("SALAD_OF_THE_DAY", "Салат дня"))),
                        new BusinessLunchOffer.Course("SOUP", "Суп", List.of(new BusinessLunchOffer.Dish("SOUP_OF_THE_DAY", "Суп дня"))),
                        new BusinessLunchOffer.Course("MAIN", "Горячее", List.of(new BusinessLunchOffer.Dish("MAIN_OF_THE_DAY", "Горячее дня")))
                ),
                sets(null, null, null)
        );
    }

    /** A venue with no sets: every dish has its own price, the guest takes what they like. */
    public static BusinessLunchOffer aLaCarte() {
        return new BusinessLunchOffer(
                "CARTE",
                "Carte",
                true,
                WEEKDAYS,
                LocalTime.of(12, 0),
                LocalTime.of(16, 0),
                90,
                "",
                List.of(
                        new BusinessLunchOffer.Course("SALAD", "Салаты", List.of(
                                new BusinessLunchOffer.Dish("NICOISE", "Нисуаз", 290, "170 г"),
                                new BusinessLunchOffer.Dish("CAESAR", "Цезарь с цыплёнком", 290, "130 г"))),
                        new BusinessLunchOffer.Course("SOUP", "Суп", List.of(
                                new BusinessLunchOffer.Dish("BORSCHT", "Борщ со сметаной", 270, "320 г"))),
                        new BusinessLunchOffer.Course("DRINKS", "Напитки", List.of(
                                new BusinessLunchOffer.Dish("MORS", "Клюквенный морс", 140, "200 мл"),
                                new BusinessLunchOffer.Dish("CAPPUCCINO", "Капучино", 220, null)))
                ),
                List.of()
        );
    }

    public static TableReservationOrder reservation(long id, long chatId, Instant startAt, Instant endAt, int guests) {
        return reservation(id, chatId, startAt, endAt, guests, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION);
    }

    public static TableReservationOrder reservation(long id, long chatId, Instant startAt, Instant endAt, int guests, TableReservationStatus status) {
        return new TableReservationOrder(
                id,
                chatId,
                chatId,
                null,
                4L,
                "4",
                "Стол 4 · у окна",
                null,
                BusinessLunchService.SEATING_LABEL,
                status,
                "TELEGRAM",
                startAt,
                endAt,
                guests,
                "Наталья",
                null,
                null,
                876857557L,
                null,
                null,
                null,
                startAt,
                startAt
        );
    }

    private static List<BusinessLunchOffer.LunchSet> sets(Integer first, Integer second, Integer third) {
        return List.of(
                new BusinessLunchOffer.LunchSet("SALAD_SOUP", "Салат + суп", first, List.of(List.of("SALAD"), List.of("SOUP"))),
                new BusinessLunchOffer.LunchSet("STARTER_MAIN", "Салат или суп + горячее", second, List.of(List.of("SALAD", "SOUP"), List.of("MAIN"))),
                new BusinessLunchOffer.LunchSet("FULL", "Салат + суп + горячее", third, List.of(List.of("SALAD"), List.of("SOUP"), List.of("MAIN")))
        );
    }
}
