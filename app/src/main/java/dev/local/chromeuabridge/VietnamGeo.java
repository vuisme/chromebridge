package dev.local.chromeuabridge;

/**
 * Provides deterministic geolocation coordinates for 20 major Vietnamese
 * provinces/cities with population-proportional weighted distribution
 * and natural-looking position jitter.
 *
 * <p>Distribution (approximate, based on 2024 population data):
 * <ul>
 *   <li>Hồ Chí Minh: 20%</li>
 *   <li>Hà Nội: 20%</li>
 *   <li>Đà Nẵng: 10%</li>
 *   <li>Remaining 50%: split across 17 provinces proportional to population</li>
 * </ul>
 *
 * <p>The same device identity (seed) always maps to the same location,
 * ensuring consistency across Chrome restarts.
 */
final class VietnamGeo {
    final double latitude;
    final double longitude;
    final double accuracy;
    final String city;

    private VietnamGeo(double latitude, double longitude, double accuracy, String city) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracy = accuracy;
        this.city = city;
    }

    // ── City/Province data ──────────────────────────────────────────────
    //
    // Population figures (approx. 2024, millions):
    //   HCM ~14.0  |  HN ~8.8   |  HP ~4.7   |  TH ~4.3  |  NA ~3.8
    //   DN ~1.2    |  DNai ~3.2  |  BD ~2.7   |  AG ~1.9  |  KH ~1.2
    //   CT ~1.3    |  LongAn ~1.8| HaTinh~1.3 | ThuaThien~1.2 | QN ~1.5
    //   BinhThuan~1.3| KienGiang~1.8| DakLak~2.0| LamDong~1.3 | GiaLai~1.5
    //
    // Fixed allocation: HCM=20, HN=20, DN=10.
    // Remaining 50 points distributed proportionally among 17 provinces
    // based on population share of those 17 provinces combined (~36.7M total):
    //   HP=6, TH=6, NA=5, DNai=4, BD=4, AG=3, KG=2, LA=2,
    //   DL=2, QN=2, CT=2, HT=2, Hue=2, BT=2, GL=2, DLE=2, KH=2

    private static final double[][] CITY_COORDS = {
            // ── Top 3 (fixed allocation) ───────────────────────────────
            {10.8231, 106.6297},   //  0  Hồ Chí Minh
            {21.0285, 105.8542},   //  1  Hà Nội
            {16.0544, 108.2022},   //  2  Đà Nẵng

            // ── Provinces by population (descending) ───────────────────
            {20.8449, 106.6881},   //  3  Hải Phòng
            {19.8075, 105.7764},   //  4  Thanh Hóa
            {18.6790, 105.6813},   //  5  Nghệ An
            {10.9574, 106.8426},   //  6  Đồng Nai (Biên Hòa center)
            {11.1671, 106.6500},   //  7  Bình Dương (Thủ Dầu Một)
            {10.3860, 105.4360},   //  8  An Giang (Long Xuyên)
            {10.0125, 105.0809},   //  9  Kiên Giang (Rạch Giá)
            {10.5360, 106.4130},   // 10  Long An (Tân An)
            {12.6814, 108.0378},   // 11  Đắk Lắk (Buôn Ma Thuột)
            {15.5600, 108.4800},   // 12  Quảng Nam
            {10.0452, 105.7469},   // 13  Cần Thơ
            {18.3560, 105.8880},   // 14  Hà Tĩnh
            {16.4637, 107.5909},   // 15  Thừa Thiên Huế
            {10.9330, 108.1000},   // 16  Bình Thuận (Phan Thiết)
            {13.9833, 108.0000},   // 17  Gia Lai (Pleiku)
            {11.9404, 108.4583},   // 18  Lâm Đồng (Đà Lạt)
            {12.2388, 109.1967},   // 19  Khánh Hòa (Nha Trang)
    };

    private static final String[] CITY_NAMES = {
            "Ho Chi Minh",    // 0
            "Ha Noi",         // 1
            "Da Nang",        // 2
            "Hai Phong",      // 3
            "Thanh Hoa",      // 4
            "Nghe An",        // 5
            "Dong Nai",       // 6
            "Binh Duong",     // 7
            "An Giang",       // 8
            "Kien Giang",     // 9
            "Long An",        // 10
            "Dak Lak",        // 11
            "Quang Nam",      // 12
            "Can Tho",        // 13
            "Ha Tinh",        // 14
            "Thua Thien Hue", // 15
            "Binh Thuan",     // 16
            "Gia Lai",        // 17
            "Lam Dong",       // 18
            "Khanh Hoa",      // 19
    };

    // Cumulative weights (sum = 100).
    // HCM=20, HN=20, DN=10,
    // HP=6, TH=6, NA=5, DNai=4, BD=4, AG=3, KG=2, LA=2,
    // DL=2, QN=2, CT=2, HT=2, Hue=2, BT=2, GL=2, LD=2, KH=2
    private static final int[] CUMULATIVE_WEIGHTS = {
            20,  // HCM
            40,  // HN
            50,  // DN
            56,  // HP
            62,  // TH
            67,  // NA
            71,  // DNai
            75,  // BD
            78,  // AG
            80,  // KG
            82,  // LA
            84,  // DL (Dak Lak)
            86,  // QN
            88,  // CT
            90,  // HT
            92,  // Hue
            94,  // BT
            96,  // GL
            98,  // LD (Lam Dong)
            100, // KH
    };

    /**
     * Select a Vietnamese province/city and generate jittered coordinates
     * based on a stable seed string (typically derived from device identity).
     * The same seed always produces the same location.
     *
     * <p>Jitter radius: ±0.08 degrees ≈ ±9 km, simulating natural variation
     * within a province/city center area.
     *
     * @param seed stable identity string for deterministic selection
     * @return geo location with province name and jittered coordinates
     */
    static VietnamGeo forProfile(String seed) {
        int hash = Math.floorMod(seed.hashCode(), 100);

        // Weighted city/province selection
        int cityIndex = 0;
        for (int i = 0; i < CUMULATIVE_WEIGHTS.length; i++) {
            if (hash < CUMULATIVE_WEIGHTS[i]) {
                cityIndex = i;
                break;
            }
        }

        // Deterministic jitter: ±0.08 degrees ≈ ±9 km radius
        // Use a secondary hash so jitter is independent of city selection
        int jitterHash = (seed + "|geo_jitter").hashCode();
        double latJitter = Math.floorMod(jitterHash, 161) / 1000.0 - 0.080;
        double lngJitter = Math.floorMod(jitterHash / 161, 161) / 1000.0 - 0.080;

        double lat = CITY_COORDS[cityIndex][0] + latJitter;
        double lng = CITY_COORDS[cityIndex][1] + lngJitter;

        // Accuracy between 20–150 m (looks natural for mobile GPS/WiFi)
        double accuracy = 20.0 + Math.floorMod(jitterHash / (161 * 161), 131);

        return new VietnamGeo(lat, lng, accuracy, CITY_NAMES[cityIndex]);
    }
}
