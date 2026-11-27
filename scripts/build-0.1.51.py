from argparse import ArgumentParser
from hashlib import sha256
from io import BytesIO
from pathlib import Path
import json
from zipfile import ZipFile


FLIGHT_BASE_SHA256 = "f1c0b426cae28010edf5ff7f4ce54995c2cdf32fffe780e1ddbcc70087560fef"
SURVEY_CLASSES = (
    "baritone/launch/mixins/MixinElytraSurveyScreen.class",
    "baritone/process/elytra/ElytraKeybinds.class",
    "baritone/process/elytra/ElytraSurvey.class",
    "baritone/process/elytra/ElytraSurvey$Session.class",
    "baritone/process/elytra/ElytraSurveyHistory.class",
    "baritone/process/elytra/ElytraSurveyPlan.class",
    "baritone/process/elytra/ElytraSurveyPlan$Config.class",
    "baritone/process/elytra/ElytraSurveyPlan$Point.class",
    "baritone/process/elytra/ElytraSurveyScreen.class",
    "baritone/process/elytra/ElytraSurveyScreen$SurveyDraft.class",
)
MIXIN_CONFIG = "mixins.baritone.json"
METADATA = "fabric.mod.json"


def read_verified(path, expected_digest):
    data = path.read_bytes()
    if sha256(data).hexdigest() != expected_digest:
        raise ValueError(f"Unexpected SHA-256 for {path}")
    return BytesIO(data)


def build(flight_base, survey_build):
    result = BytesIO()
    with (
        ZipFile(read_verified(flight_base, FLIGHT_BASE_SHA256)) as flight,
        ZipFile(survey_build) as survey,
        ZipFile(result, "w") as combined,
    ):
        replacements = {name: survey.read(name) for name in SURVEY_CLASSES}
        config = json.loads(flight.read(MIXIN_CONFIG))
        config["client"].append("MixinElytraSurveyScreen")
        replacements[MIXIN_CONFIG] = (json.dumps(config, indent=2) + "\n").encode()
        metadata = flight.read(METADATA)
        version = b'"version": "0.1.50"'
        if metadata.count(version) != 1:
            raise ValueError("Expected BetterBaritone 0.1.50 metadata")
        replacements[METADATA] = metadata.replace(version, b'"version": "0.1.51"')
        combined.comment = flight.comment
        for entry in flight.infolist():
            data = replacements.pop(entry.filename) if entry.filename in replacements else flight.read(entry)
            combined.writestr(entry, data)
        for name, data in replacements.items():
            combined.writestr(survey.getinfo(name), data)
    return result.getvalue()


def main():
    parser = ArgumentParser(description="Build BetterBaritone 0.1.51 from its 0.1.50 base and a remapped build of the exploration module.")
    parser.add_argument("flight_base", type=Path)
    parser.add_argument("survey_build", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    jar = build(args.flight_base, args.survey_build)
    with args.output.open("xb") as output:
        output.write(jar)
    print(f"{args.output}  SHA-256 {sha256(jar).hexdigest()}")


if __name__ == "__main__":
    main()
