#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include "api.h"

#define CRYPTO_PUBLICKEYBYTES PQCLEAN_MLKEM512_CLEAN_CRYPTO_PUBLICKEYBYTES
#define CRYPTO_SECRETKEYBYTES PQCLEAN_MLKEM512_CLEAN_CRYPTO_SECRETKEYBYTES
#define CRYPTO_CIPHERTEXTBYTES PQCLEAN_MLKEM512_CLEAN_CRYPTO_CIPHERTEXTBYTES
#define CRYPTO_BYTES PQCLEAN_MLKEM512_CLEAN_CRYPTO_BYTES

#define crypto_kem_keypair PQCLEAN_MLKEM512_CLEAN_crypto_kem_keypair
#define crypto_kem_enc     PQCLEAN_MLKEM512_CLEAN_crypto_kem_enc
#define crypto_kem_dec     PQCLEAN_MLKEM512_CLEAN_crypto_kem_dec

void print_hex(const uint8_t* data, size_t len) {
    for (size_t i = 0; i < len; i++) {
        printf("%02X", data[i]);
    }
    printf("\n");
}

int main() {
    uint8_t pk[CRYPTO_PUBLICKEYBYTES];
    uint8_t sk[CRYPTO_SECRETKEYBYTES];
    uint8_t ct[CRYPTO_CIPHERTEXTBYTES];
    uint8_t ss_enc[CRYPTO_BYTES];
    uint8_t ss_dec[CRYPTO_BYTES];

    if (crypto_kem_keypair(pk, sk) != 0) {
        printf("Key generation failed\n");
        return 1;
    }

    if (crypto_kem_enc(ct, ss_enc, pk) != 0) {
        printf("Encapsulation failed\n");
        return 1;
    }

    // 오류 유도 테스트: 암호문 일부를 망가뜨림
    //ct[0] ^= 0xFF;

    if (crypto_kem_dec(ss_dec, ct, sk) != 0) {
        printf("Decapsulation failed\n");
        return 1;
    }

    printf("Encapsulated shared secret: ");
    print_hex(ss_enc, CRYPTO_BYTES);

    printf("Decapsulated shared secret: ");
    print_hex(ss_dec, CRYPTO_BYTES);

    if (memcmp(ss_enc, ss_dec, CRYPTO_BYTES) == 0) {
        printf("Shared secret matches!\n");
    }
    else {
        printf("Shared secret mismatch.\n");
    }

    return 0;
}
