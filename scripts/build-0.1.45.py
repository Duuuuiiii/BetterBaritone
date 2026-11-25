from argparse import ArgumentParser
from hashlib import sha256
from io import BytesIO
from pathlib import Path
from zipfile import ZipFile


FLIGHT_BASE_SHA256 = "cca6b80904272f9e97c7caebd411eb139c6efa5b93831c745274d41bcc3cc1b5"
MINING_BASE_SHA256 = "fcfeeb9dcfb7cf1dba83fbfd19c916f1fd062e3c1fbc3a7b08a58986b2075749"
MINING_CLASSES = (
    "baritone/process/MineProcess.class",
    "baritone/process/MineProcess$1.class",
    "baritone/process/MineProcess$GoalThreeBlocks.class",
)
METADATA = "fabric.mod.json"


def read_verified(path, expected_digest):
    data = path.read_bytes()
    if sha256(data).hexdigest() != expected_digest:
        raise ValueError(f"Unexpected SHA-256 for {path}")
    return BytesIO(data)


def build(flight_base, mining_base):
    result = BytesIO()
    with (
        ZipFile(read_verified(flight_base, FLIGHT_BASE_SHA256)) as flight,
        ZipFile(read_verified(mining_base, MINING_BASE_SHA256)) as mining,
        ZipFile(result, "w") as combined,
    ):
        replacements = {name: mining.read(name) for name in MINING_CLASSES}
        metadata = flight.read(METADATA)
        version = b'"version": "0.1.12"'
        if metadata.count(version) != 1:
            raise ValueError("Expected BetterBaritone 0.1.12 metadata")
        replacements[METADATA] = metadata.replace(version, b'"version": "0.1.45"')
        combined.comment = flight.comment
        for entry in flight.infolist():
            data = replacements[entry.filename] if entry.filename in replacements else flight.read(entry)
            combined.writestr(entry, data)
    return result.getvalue()


def main():
    parser = ArgumentParser(description="Build BetterBaritone 0.1.45 from the archived 0.1.12 and 0.1.16 full JARs.")
    parser.add_argument("flight_base", type=Path)
    parser.add_argument("mining_base", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    jar = build(args.flight_base, args.mining_base)
    with args.output.open("xb") as output:
        output.write(jar)
    print(f"{args.output}  SHA-256 {sha256(jar).hexdigest()}")


if __name__ == "__main__":
    main()
