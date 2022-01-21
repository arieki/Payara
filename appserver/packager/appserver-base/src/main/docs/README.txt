Thank you for downloading Payara Server 5.36.0!

Here are a few short steps to get you started...


0. Prerequisite
===============

Payara Server currently supports the following Java Virtual Machines:

* Oracle JDK8 (u162+), Oracle JDK 11 (11.0.5+)
* Azul Zulu JDK8 (u162+), Azul Zulu JDK 11 (11.0.5u10+)
* OpenJDK JDK8 (u162+), OpenJDK 11 (11.0.5+)

TLS 1.3 is supported on JDK 8 with Azul Zulu 1.8.222+ only and all JDK 11 versions.

1. Installing Payara Server
===========================

Installing Payara Server is just a matter of unzipping the Payara Server archive in the desired directory. Since you are reading this, you have probably already unzipped Payara Server. If not, just type the following command in the directory where you want Payara Server to be installed: jar xvf payara-5.36.0.zip

The default domain called 'domain1' is already installed and preconfigured.


2. Starting Payara Server
=========================

The 'asadmin' command-line utility is used to control and manage Payara Server (start, stop, configure, deploy applications, etc.).

To start Payara Server, just go in the directory where Payara Server is located and type:
        On Unix: payara5/bin/asadmin start-domain
        On Windows: payara5\bin\asadmin start-domain

After a few seconds, Payara Server will be up and ready to accept requests. The default 'domain1' domain is configured to listen on port 8080. In your browser, go to http://localhost:8080 to see the default landing page.

To manage Payara Server, just go to web administration console: http://localhost:4848


3. Stopping Payara Server
=========================

To stop Payara Server, just issue the following command:
        On Unix: payara5/bin/asadmin stop-domain
        On Windows: payara5\bin\asadmin stop-domain


4. Where to go next?
====================

Open the following in your browser: https://docs.payara.fish/. It contains useful information such as the details about the Payara Project, links to the Payara Server Documentation, etc.


Make sure to also check the Payara Server 5.36.0 Release Notes as they contain important information: https://docs.payara.fish/enterprise/docs/5.36.0/release-notes/release-notes-36-0.html


5. Documentation
================

Payara Server 5.36.0 Release Notes: https://docs.payara.fish/enterprise/docs/5.36.0/release-notes/release-notes-36-0.html

Payara Server Documentation: https://docs.payara.fish/

Payara Server GitHub Project: https://github.com/payara/Payara

Payara Community: http://www.payara.fish/community


6. Need Additional Help?
============
As a Payara Platform Enterprise customer, you have exclusive access to our add-on consultancy services through Payara Accelerator. Expert consultants customise solutions to meet your  needs with on-premise or remote consultancy offerings.

Accelerate your Payara Platform deployment, migration, or business growth through:

* Migration assistance
* Application server upgrades
* Health checks
* Configuration reviews
* Monitoring
* Performance tuning
* Troubleshooting

Learn more about Payara Accelerator: https://www.payara.fish/support/consultancy/


7. Follow us
============

Make sure to follow @Payara_Fish on Twitter and read The Payara Tech Blog (http://blog.payara.fish/) to get the latest news on Payara.
