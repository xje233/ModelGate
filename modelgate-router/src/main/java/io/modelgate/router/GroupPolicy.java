package io.modelgate.router;

/**
 * How one model group picks among its deployments.
 *
 * @param canaryDeployment deployment name that serves the experimental arm; {@code null} means
 *                         no canary and the group behaves as a plain weighted pool
 * @param canaryPercentage share of traffic (0-100) sent to the canary arm
 */
public record GroupPolicy(String canaryDeployment, int canaryPercentage) {

    private static final GroupPolicy PLAIN = new GroupPolicy(null, 0);

    public static GroupPolicy plain() {
        return PLAIN;
    }

    public static GroupPolicy canary(String deployment, int percentage) {
        return new GroupPolicy(deployment, Math.max(0, Math.min(100, percentage)));
    }

    public boolean hasCanary() {
        return canaryDeployment != null && !canaryDeployment.isBlank();
    }
}
