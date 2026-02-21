# v2.1.4
* bump up ldlib2 and remove deprecated APIs
* added command to covert photon1 fx into photon2 fx format (thanks @Cdogsnappy)
  * `/photon_client convert`, which will grab all `.fx` files from the directory in `ldlib2/assets/photon/fx_old` and convert them to their photon 2 equivalent, placing the result in the neighboring /fx directory.