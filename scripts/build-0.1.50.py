from argparse import ArgumentParser
from hashlib import sha256
from io import BytesIO
from pathlib import Path
from zipfile import ZipFile


FLIGHT_BASE_SHA256 = "fbab00e89f7d2f44987c21201d0e7b36821e9af75a0e41aa7854f9dae03f7ac5"
ROUTE_CLASSES = (
    "baritone/process/elytra/NetherPathfinderContext.class",
    "baritone/process/elytra/LongRangeElytraRoute.class",
)
METADATA = "fabric.mod.json"


def read_verified(path, expected_digest):
    data = path.read_bytes()
    if sha256(data).hexdigest() != expected_digest:
        raise ValueError(f"Unexpected SHA-256 for {path}")
    return BytesIO(data)


def build(flight_base, planner_build):
    result = BytesIO()
    with (
        ZipFile(read_verified(flight_base, FLIGHT_BASE_SHA256)) as flight,
        ZipFile(planner_build) as planner,
        ZipFile(result, "w") as combined,
    ):
        replacements = {name: planner.read(name) for name in ROUTE_CLASSES}
        metadata = flight.read(METADATA)
        version = b'"version": "0.1.49"'
        if metadata.count(version) != 1:
            raise ValueError("Expected BetterBaritone 0.1.49 metadata")
        replacements[METADATA] = metadata.replace(version, b'"version": "0.1.50"')
        combined.comment = flight.comment
        for entry in flight.infolist():
            data = replacements.pop(entry.filename) if entry.filename in replacements else flight.read(entry)
            combined.writestr(entry, data)
        for name, data in replacements.items():
            combined.writestr(planner.getinfo(name), data)
    return result.getvalue()


def main():
    parser = ArgumentParser(description="Build BetterBaritone 0.1.50 from its 0.1.49 base and a remapped build of the updated route planner.")
    parser.add_argument("flight_base", type=Path)
    parser.add_argument("planner_build", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    jar = build(args.flight_base, args.planner_build)
    with args.output.open("xb") as output:
        output.write(jar)
    print(f"{args.output}  SHA-256 {sha256(jar).hexdigest()}")


if __name__ == "__main__":
    main()
