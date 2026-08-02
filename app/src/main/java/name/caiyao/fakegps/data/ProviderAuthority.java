package name.caiyao.fakegps.data;

/** Pure authority construction shared by manifest-driven app variants and the UriMatcher. */
public final class ProviderAuthority {
    private ProviderAuthority() {}

    public static String forApplicationId(String applicationId) {
        if (applicationId == null || applicationId.isEmpty()) {
            throw new IllegalArgumentException("applicationId must not be empty");
        }
        return applicationId + ".data.AppInfoProvider";
    }
}
