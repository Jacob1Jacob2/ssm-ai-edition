SSM AI Edition - public hosting migration
Prepared 2026-11-13. Oracle deployment is NOT installed yet.

START HERE
Create your free account at https://www.oracle.com/cloud/free/.
The next handoff is the Oracle account being ready. The account login, card
verification and MFA stay with you. After that we can create the VM and deploy.
The files in this folder are ready for that step; your local server still runs
0.1.0-ai-personalities-20261113. Its plugin was not replaced by this migration.

WHAT IS PREPARED
- Paper 26.3 build 245, retaining the current Minecraft/plugin compatibility.
- SSM 0.1.0-ai-bot-realism-20261114 (rebuild with build-bundle.ps1), LibsDisguises 26.10.24, PacketEvents 2.14.0.
- 45 maps, customized saved lobby, map pictures, podium, bot skins, preferences,
  wins, operators and bans. No test plugins or exported player histories.
- Java 25 installation and a systemd service that starts at boot and restarts
  after a crash. /opt/ssm/server stores the running server.
- Public resource-pack HTTP address, separate from Minecraft on port 25566.
- Public-player /debug restriction. Existing operator tools stay restricted.
- Public profile: at most 2 simultaneous matches and 16 total fighters,
  counting bots. Busy players receive a message and can retry after a game.
  One 16-fighter game uses the whole fighter allowance. Hub users are separate.
- 4 GB JVM heap, 8 GB service memory ceiling, view distance 8, simulation 6.
  These are starting limits, not a measured public concurrency guarantee.

VM SETTINGS
Use a free-eligible Ubuntu 24.04 ARM64 image, VM.Standard.A1.Flex, 2 OCPUs,
12 GB RAM, and a 50 GB boot disk in your home region. Assign a public IPv4
address and a public subnet with a route to an Internet Gateway. Save the SSH
private key on your computer; do not paste it in chat. Use the ubuntu SSH user.
Use only the Always Free allocation, not trial-only resources or paid shapes.
If A1 is out of capacity, try another availability domain in the same home
region or wait. The 1 GB AMD Micro is too small for this prepared profile.

Oracle's current documentation lists 2 OCPUs/12 GB for free tenancies and
200 GB total free block storage. Available capacity and idle-instance reclaim
mean this is not guaranteed uninterrupted hosting. Keep off-VM backups.
https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm

NETWORK
Add stateful inbound rules in the VM's Network Security Group (or its subnet's
security list):
  TCP 25565 from 0.0.0.0/0  Minecraft Java clients
  TCP 25566 from 0.0.0.0/0  Generated SSM resource pack
  TCP 22 from your own public IP/32  SSH administration
Retain outbound access for package downloads, Minecraft authentication, skins
and dependency downloads. Do not expose RCON or the management API.

The VM's own OS firewall must also permit 25565 and 25566. Inspect the image
before changing it: sudo iptables -S; sudo ufw status. On the usual OCI Ubuntu
image using iptables, insert these before any final reject rule:
  sudo iptables -C INPUT -p tcp --dport 25565 -j ACCEPT || sudo iptables -I INPUT 1 -p tcp --dport 25565 -j ACCEPT
  sudo iptables -C INPUT -p tcp --dport 25566 -j ACCEPT || sudo iptables -I INPUT 1 -p tcp --dport 25566 -j ACCEPT
Persist with sudo netfilter-persistent save if that service is installed.
If UFW is active, use its rules instead: sudo ufw allow 25565/tcp and
sudo ufw allow 25566/tcp. Do not flush the firewall or replace SSH rules.
https://docs.oracle.com/en-us/iaas/Content/Network/Concepts/securityrules.htm

UPLOAD AND INSTALL (once the VM exists)
Replace KEY_PATH and PUBLIC_IP below with the actual values. Verify the VM's
SSH host fingerprint on the initial connection. Commands on your Windows PC:
  ssh -i "KEY_PATH" ubuntu@PUBLIC_IP "mkdir -p ~/ssm-deploy"
  scp -i "KEY_PATH" "C:\Users\jacob\Documents\SSM AI Edition\public-hosting\*" ubuntu@PUBLIC_IP:~/ssm-deploy/
  ssh -i "KEY_PATH" ubuntu@PUBLIC_IP

Then on Ubuntu:
  cd ~/ssm-deploy
  sudo python3 install.py --public-host PUBLIC_IP --open-firewall --start
  (--open-firewall adds the two iptables rules above and saves them)
  sudo systemctl status ssm --no-pager
  sudo journalctl -u ssm -n 100 --no-pager

The installer verifies the archive checksum before installation and refuses
to overwrite an existing /opt/ssm installation. It installs Java 25 using
Adoptium's signed repository, creates an unprivileged ssm service user, and
writes http://PUBLIC_IP:25566/ssm-pack.zip into the plugin's configuration.
This full-server archive includes the existing server's accepted EULA setting.
https://adoptium.net/installation/linux

VERIFY FROM OUTSIDE THE VM
Run on a different computer using Python 3.11 or newer:
  python verify.py PUBLIC_IP
  or, on Windows without Python: powershell -ExecutionPolicy Bypass -File verify.ps1 -Address PUBLIC_IP
This checks Minecraft server status and downloads/opens the resource pack,
including all 45 map pictures. It does not authenticate a Minecraft player.
Then join PUBLIC_IP from a licensed Minecraft Java client matching 26.3,
accept the pack, start a bot match as a non-operator, finish it, return to the
hub, and try a second concurrent match. Check MSPT/TPS before increasing caps.
The system is not considered publicly deployed until those remote checks pass.

OPERATING THE SERVER
  sudo systemctl stop ssm
  sudo systemctl start ssm
  sudo systemctl restart ssm
  sudo journalctl -u ssm -f
The service's SIGINT shutdown saves the world. Avoid killing Java forcibly.
In-game operator accounts are retained from the local ops.json; normal players
need no operator access to choose kits, personalities, maps and start games.
The current AI personalities are implemented by the plugin; no paid LLM API
or external application database is required.

Heap settings: /etc/ssm/ssm.env
Match limits: /opt/ssm/server/plugins/super-smash-mobs-brawl/config.yml
Pack address: /opt/ssm/server/plugins/super-smash-mobs-brawl/resource-pack.properties
Restart the service after changing these. If the public IP changes, update
the pack URL and players' server address. The Minecraft and HTTP ports must
match the firewall rules. Minecraft online-mode remains true.

BACKUP AND RECOVERY
Run sudo python3 /opt/ssm/backup.py manually during a quiet period. It stops
the server, makes a consistent archive in /var/backups/ssm and restarts only
if it was running before. Copy the archive to your PC; no automatic backup
schedule or retention cleanup has been enabled. Check disk space periodically.
To recover, stop the service, retain the damaged server folder under a new
name, extract the backup into /opt/ssm, restore ownership with
sudo chown -R ssm:ssm /opt/ssm/server, and start the service. Java and service
scripts remain in place. Paper recreates omitted caches/dependencies using
outbound network access. Restore the public pack URL if the VM changed.

VALIDATION AND CONTENTS
manifest.json records versions, checksums and build test counts.
local-validation.json records the separate extracted-bundle startup check.
server.tar.gz is the actual upload payload; treat it as private server data.
Local tests do not prove Linux ARM64 behavior, Oracle reachability, client
rendering, remote login or sustained public load. Those checks await the VM.
The source and original server snapshot are backed up under:
C:\Users\jacob\Documents\SSM AI Edition\backups\public-hosting-2026-11-13

Before deployment, refresh the snapshot if local maps, wins or settings have
changed since this copy was made, so the public server does not start stale.
