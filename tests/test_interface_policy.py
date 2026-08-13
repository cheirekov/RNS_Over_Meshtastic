from RNS.Interfaces.TCPInterface import TCPClientInterface

from rns_meshtastic.interface import MeshtasticPeerInterface, RNSMeshtasticInterface


def test_default_ifac_size_matches_standard_reticulum_tcp():
    assert RNSMeshtasticInterface.DEFAULT_IFAC_SIZE == 16
    assert RNSMeshtasticInterface.DEFAULT_IFAC_SIZE == TCPClientInterface.DEFAULT_IFAC_SIZE


def test_dynamic_peer_uses_parent_ifac_default():
    assert MeshtasticPeerInterface.DEFAULT_IFAC_SIZE == RNSMeshtasticInterface.DEFAULT_IFAC_SIZE
