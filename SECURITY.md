# Security policy

Report vulnerabilities privately to the repository maintainers. Do not include
access tokens or alarm codes in issues, logs, screenshots, or pull requests.

The app accepts only HTTPS/WSS Home Assistant endpoints and stores the token
encrypted with an Android Keystore key. Removing the connection deletes both
the encrypted envelope and its key.
