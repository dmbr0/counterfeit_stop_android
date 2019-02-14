# tangem-demo
Demo application for tangemcard libraries

Simple example how to use tangemcard libraries to read Tangem cards on android smartphones with NFC.

This example show:
1. Setup permissions to access NFC reader (in AndroidManifes.xml and check&request permissions on MainActivity)
2. Setup intent filters in AndroidManifes.xml to startup application by simple tap on Tangem card
3. Setup local enviroment (PINStorage, FirmwareStorage, Issuers and etc) to use tangemcard libraries
4. Setup scan card activity layout (MainActivity) to show user how to tap card according to the phone's NFC reader location
5. Setup NFC and tangem card callbacks to run ReadCardInfoTask and process read result
