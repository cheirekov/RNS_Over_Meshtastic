"""Reticulum external-interface loader shim.

Copy this file to ``<reticulum-config>/interfaces`` and run rnsd from the same
Python environment in which ``rns-over-meshtastic`` is installed.
"""

from rns_meshtastic.interface import RNSMeshtasticInterface

interface_class = RNSMeshtasticInterface
