# ELM327 emulator (Ircama/ELM327-emulator, CC BY-NC-SA 4.0) as a TCP service for testing the apps without a car.
FROM python:3.13-slim
RUN pip install --no-cache-dir ELM327-emulator==4.0.0
# Fault scenario driver (service elm327-faults); not used by the normal emulator.
COPY faults.sh /opt/faults.sh
USER nobody
# elm.log is written to the working directory; the container root file system is read-only.
WORKDIR /tmp
# Batch mode runs in the foreground without a console; the first output line names the device, then requests are logged.
# Restart loop: ELM327-emulator 4.0.0 ends when a client drops the connection while the emulator echoes a character
# (BrokenPipeError/ConnectionResetError in read_from_device, exit 0). Restarting in-process keeps the port down for
# under a second instead of waiting for Docker's restart policy.
CMD ["sh", "-c", "while :; do python -m elm -n 35000 -i 0.0.0.0 -s car -b /dev/stdout; echo 'ELM327-emulator ended, restarting'; sleep 0.5; done"]
