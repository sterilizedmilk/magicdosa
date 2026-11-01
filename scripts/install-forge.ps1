# Installs the Forge submodule (at the commit this repository pins) into the local Maven repository.
# Run again whenever the forge submodule moves. flatten is needed: Forge only resolves ${revision}
# in its poms at deploy time, and an unflattened pom cannot be read by magicdosa's build.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
git -C $root submodule update --init forge
Push-Location (Join-Path $root "forge")
try {
    mvn -pl forge-gui-desktop -am -DskipTests org.codehaus.mojo:flatten-maven-plugin:1.6.0:flatten install
} finally {
    Pop-Location
}
