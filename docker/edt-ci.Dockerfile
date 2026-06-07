# Headless 1C:EDT + MCP server image for CI (e2e / conformance gates).
#
# Assembles EDT from its PUBLIC p2 update-site via `p2 director` into a base
# Eclipse Platform — NO manual product download, no 1C account at build time
# (the same p2 our Tycho build already pulls from). EDT runs under Xvfb (it is an
# Eclipse/SWT GUI app; the workbench needs a display, virtual is fine).
#
# ⚠️ UNVERIFIED: this Dockerfile is grounded in the live p2 metadata (the IUs and
# versions were read from the repo) but has NOT been built+booted end-to-end yet.
# Before trusting it: build it, run it, confirm the workbench starts under Xvfb
# and the MCP server binds :8765. See docker/README.md.
#
# Build (from repo root, with the MCP bundle jar already built by compile.sh):
#   docker build -f docker/edt-ci.Dockerfile \
#     --build-arg MCP_BUNDLE=mcp/bundles/com.ditrix.edt.mcp.server/target/com.ditrix.edt.mcp.server-1.0.0-SNAPSHOT.jar \
#     -t edt-ci .

FROM eclipse-temurin:17-jammy

# --- EDT / Eclipse coordinates (pinned; EDT 2025.2 <-> Eclipse 4.30 / 2023-12) ---
ARG EDT_P2=https://edt.1c.ru/downloads/releases/ruby/2025.2/
ARG ECLIPSE_P2=https://download.eclipse.org/releases/2023-12/
ARG ECLIPSE_DROP=https://archive.eclipse.org/eclipse/downloads/drops4/R-4.30-202312010110/eclipse-platform-4.30-linux-gtk-x86_64.tar.gz
# EDT product IU (verified: com._1c.g5.v8.dt.rcp is the runnable IDE product) +
# the 1C platform-support feature matching the configs you test (8.5.1 here).
ARG EDT_IU=com._1c.g5.v8.dt.rcp,com._1c.g5.v8.dt.platform.support_v8.5.1.feature.feature.group

# --- SWT/GTK + Xvfb runtime deps for a headless Eclipse on Ubuntu 22.04 ---
RUN apt-get update && apt-get install -y --no-install-recommends \
      xvfb \
      libgtk-3-0 libgl1 libglu1-mesa \
      libxtst6 libxi6 libxrender1 libxrandr2 libxext6 libx11-6 libxfixes3 \
      libcairo2 libpango-1.0-0 libpangocairo-1.0-0 libgdk-pixbuf-2.0-0 libglib2.0-0 \
      libnss3 libnspr4 libwebkit2gtk-4.1-0 libsecret-1-0 \
      fontconfig fonts-dejavu-core ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*

# --- Base Eclipse Platform 4.30 (host for the p2 director) ---
RUN curl -fsSL -o /tmp/ecl.tgz "$ECLIPSE_DROP" \
 && tar xzf /tmp/ecl.tgz -C /opt \
 && rm /tmp/ecl.tgz
# -> /opt/eclipse

# --- Install the EDT product from the public p2 into /opt/edt ---
# -p2.os/-p2.ws/-p2.arch are REQUIRED: the Linux launcher + 1cedt.ini IUs are
# os/ws/arch-filtered, otherwise you get plugins but no working native launcher.
RUN /opt/eclipse/eclipse -nosplash -consoleLog \
      -application org.eclipse.equinox.p2.director \
      -repository "$EDT_P2,$ECLIPSE_P2" \
      -installIU "$EDT_IU" \
      -destination /opt/edt -profile EDTProfile \
      -profileProperties org.eclipse.update.install.features=true \
      -p2.os linux -p2.ws gtk -p2.arch x86_64 \
      -roaming -tag init

# --- Drop in the MCP server bundle (build it first with source/compile.sh) ---
# dropins/ is auto-discovered by Eclipse; alternatively install via a p2 repo.
ARG MCP_BUNDLE
COPY ${MCP_BUNDLE} /opt/edt/dropins/

COPY docker/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

ENV EDT_WORKSPACE=/opt/edt-workspace
EXPOSE 8765
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
