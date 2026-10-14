package museon_online.astor_butler.domain.journey;

/** Whether a bot-first message may go out. */
public sealed interface ProactiveDecision permits ProactiveDecision.Allow, ProactiveDecision.Silent {

    record Allow() implements ProactiveDecision {
    }

    record Silent(String reason) implements ProactiveDecision {
    }

    static ProactiveDecision allow() {
        return new Allow();
    }
}
