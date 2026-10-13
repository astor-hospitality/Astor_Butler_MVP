package museon_online.astor_butler.integration.saby;

/** Weight and nutrition of one portion, from the dish's tech card in Saby (attributes per 100 g × portion grams). */
public record SabyNutrition(int grams, int kcal, int protein, int fat, int carbs) {
}
