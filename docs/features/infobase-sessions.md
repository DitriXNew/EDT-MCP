# Infobase sessions and database updates

Before applying `update_database` to a standalone-server application, call
`infobase_sessions(action='list', projectName='...', applicationId='...')`. A readable empty
`sessions` array proves no sessions exist; `reachable=false` instead names why inspection was
impossible and must never be read as an empty list. Any non-agent session blocks the default
update preflight. Clear blockers with
`infobase_sessions(action='terminate', projectName='...', applicationId='...', all=true,
confirm=true)`, then retry the update. A `Designer` session may be EDT's update agent OR a
human Configurator, and EDT exposes no discriminator. It is therefore not treated as a
blocker — EDT's own agent is present during every normal update — and `all=true` always
skips it. Terminating one is possible only by its exact full session UUID.

