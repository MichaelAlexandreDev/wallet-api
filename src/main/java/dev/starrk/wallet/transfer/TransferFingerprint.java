package dev.starrk.wallet.transfer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class TransferFingerprint {
    private TransferFingerprint() {
    }

    static String from(long payerId, TransferRequest request) {
        var canonicalAmount = request.value().stripTrailingZeros().toPlainString();
        // Mesmo formato de antes da autenticação, para que chaves já gravadas continuem válidas.
        var canonicalRequest = payerId + ":" + request.payee() + ":" + canonicalAmount;
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
