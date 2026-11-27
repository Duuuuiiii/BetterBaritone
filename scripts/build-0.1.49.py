from argparse import ArgumentParser
from hashlib import sha256
from io import BytesIO
from pathlib import Path
from zipfile import ZipFile
import json


FLIGHT_BASE_SHA256 = "d2aac4259d8d8092b6da6e7a3c2af6798d10ca4e35cf2e0e752ae0446b093581"
MIXIN_NAME = "MixinElytraGroundLaunch"
MIXIN_CLASS = f"baritone/launch/mixins/{MIXIN_NAME}.class"
MIXIN_CONFIG = "mixins.baritone.json"
METADATA = "fabric.mod.json"


def read_verified(path, expected_digest):
    data = path.read_bytes()
    if sha256(data).hexdigest() != expected_digest:
        raise ValueError(f"Unexpected SHA-256 for {path}")
    return BytesIO(data)


def build(flight_base, mixin_build):
    result = BytesIO()
    with (
        ZipFile(read_verified(flight_base, FLIGHT_BASE_SHA256)) as flight,
        ZipFile(mixin_build) as donor,
        ZipFile(result, "w") as combined,
    ):
        config = json.loads(flight.read(MIXIN_CONFIG))
        config["client"].append(MIXIN_NAME)
        replacements = {
            MIXIN_CLASS: donor.read(MIXIN_CLASS),
            MIXIN_CONFIG: (json.dumps(config, indent=2) + "\n").encode(),
        }
        metadata = flight.read(METADATA)
        version = b'"version": "0.1.48"'
        if metadata.count(version) != 1:
            raise ValueError("Expected BetterBaritone 0.1.48 metadata")
        replacements[METADATA] = metadata.replace(version, b'"version": "0.1.49"')
        combined.comment = flight.comment
        for entry in flight.infolist():
            data = replacements.pop(entry.filename) if entry.filename in replacements else flight.read(entry)
            combined.writestr(entry, data)
        for name, data in replacements.items():
            combined.writestr(donor.getinfo(name), data)
    return result.getvalue()


def main():
    parser = ArgumentParser(description="Build BetterBaritone 0.1.49 from its 0.1.48 base and the remapped ground launch mixin.")
    parser.add_argument("flight_base", type=Path)
    parser.add_argument("mixin_build", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    jar = build(args.flight_base, args.mixin_build)
    with args.output.open("xb") as output:
        output.write(jar)
    print(f"{args.output}  SHA-256 {sha256(jar).hexdigest()}")


if __name__ == "__main__":
    main()
