#include "client/windows/overlay/app/gui/rhpairing.h"
#include <QCoreApplication>
#include <iostream>

int main(int argc, char** argv) {
    QCoreApplication application(argc, argv);
    // Public fixtures generated independently with Python hashlib. All inputs
    // are a 16-byte salt followed by the literal test PIN 1234, never user data.
    struct Vector { const char* name; QCryptographicHash::Algorithm algorithm; const char* salt; const char* key; };
    const Vector vectors[] = {
        {"sha256-leading-zero", QCryptographicHash::Sha256, "000000000000000000000000000000cb", "00841e3462aba1733f8c0503d1f7bb6d"},
        {"sha256-embedded-zero", QCryptographicHash::Sha256, "000000000000000000000000000001fe", "265536780b5f1b006e9c5ec51477d5b4"},
        {"sha1-leading-zero", QCryptographicHash::Sha1, "000000000000000000000000000001da", "005aa27cf1d4172edb16a611f2cb559e"},
        {"sha1-embedded-zero", QCryptographicHash::Sha1, "00000000000000000000000000000281", "cc679fc659057a7a40001aa96277c4f0"},
    };
    unsigned failures = 0;
    for (const auto& vector : vectors) {
        const QByteArray input = QByteArray::fromHex(vector.salt) + QByteArray("1234");
        const auto actual = RhPairing::deriveKey(input, vector.algorithm);
        const auto expected = QByteArray::fromHex(vector.key);
        if (actual.size() != 16 || actual != expected) {
            std::cerr << "FAIL " << vector.name << " key_bytes=" << actual.size() << " expected_bytes=16\n";
            ++failures;
        } else {
            std::cout << "PASS " << vector.name << " key_bytes=16\n";
        }
    }
    return failures ? 1 : 0;
}
