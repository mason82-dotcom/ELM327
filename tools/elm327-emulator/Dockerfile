# ELM327 emulator (Ircama/ELM327-emulator, CC BY-NC-SA 4.0) as a TCP service for testing the apps without a car.
FROM python:3.13-slim
RUN pip install --no-cache-dir ELM327-emulator==4.0.0
USER nobody
# elm.log is written to the working directory; the container root file system is read-only.
WORKDIR /tmp
# Batch mode runs in the foreground without a console; the first output line names the device, then requests are logged.
CMD ["python", "-m", "elm", "-n", "35000", "-i", "0.0.0.0", "-s", "car", "-b", "/dev/stdout"]
