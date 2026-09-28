// Resolves the mod version for the `build` job.
//
// Version semantics live in the Gradle build (axion-release); this step only
// keeps the branch-ancestry guard that cannot live inside the build: a release
// tag must point at a commit reachable from origin/main (preferred when both
// branches contain the tagged commit) or origin/dev. The chosen channel reaches
// Gradle via AIRICRAFT_RELEASE_CHANNEL, which turns plain vX.Y.Z tags on dev
// into -alpha and rejects malformed or disallowed release tags.

const gitExitCode = (exec, args) =>
	exec.exec("git", args, { ignoreReturnCode: true });

const isAncestor = async (exec, remoteRef) =>
	(await gitExitCode(exec, ["show-ref", "--verify", "--quiet", `refs/remotes/${remoteRef}`])) === 0 &&
	(await gitExitCode(exec, ["merge-base", "--is-ancestor", "HEAD", `refs/remotes/${remoteRef}`])) === 0;

export default async ({ core, exec }) => {
	const isTag = process.env.GITHUB_REF_TYPE === "tag";
	let channel = "";

	if (isTag) {
		if (await isAncestor(exec, "origin/main")) {
			channel = "main";
		}
		else if (await isAncestor(exec, "origin/dev")) {
			channel = "dev";
		}
		else {
			core.setFailed("Release tags must point to a commit on origin/dev or origin/main");
			return;
		}
	}

	const env = { ...process.env };
	if (channel) {
		env.AIRICRAFT_RELEASE_CHANNEL = channel;
	}

	const gradle = await exec.getExecOutput("./gradlew", ["-q", "printModVersion"], { env });
	const version = gradle.stdout.trim().split("\n").pop();

	core.setOutput("value", version);
	core.setOutput("prerelease", String(isTag && version.includes("-")));
	core.setOutput("channel", channel);
	core.info(`resolved mod version: ${version} (channel: ${channel || "none"})`);
};
